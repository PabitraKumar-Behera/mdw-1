/*
 * Copyright (C) 2017 CenturyLink, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.centurylink.mdw.services.asset;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONObject;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.bpm.PackageDocument;
import com.centurylink.mdw.common.service.Query;
import com.centurylink.mdw.common.service.ServiceException;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.dataaccess.AssetRevision;
import com.centurylink.mdw.dataaccess.DataAccess;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.dataaccess.DatabaseAccess;
import com.centurylink.mdw.dataaccess.VersionControl;
import com.centurylink.mdw.dataaccess.file.AssetFile;
import com.centurylink.mdw.dataaccess.file.GitDiffs;
import com.centurylink.mdw.dataaccess.file.GitDiffs.DiffType;
import com.centurylink.mdw.dataaccess.file.PackageDir;
import com.centurylink.mdw.dataaccess.file.VersionControlGit;
import com.centurylink.mdw.model.asset.AssetInfo;
import com.centurylink.mdw.model.asset.AssetPackageList;
import com.centurylink.mdw.model.asset.PackageAssets;
import com.centurylink.mdw.model.asset.PackageList;
import com.centurylink.mdw.model.workflow.Package;
import com.centurylink.mdw.services.AssetServices;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import com.centurylink.mdw.util.timer.CodeTimer;

public class AssetServicesImpl implements AssetServices {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();

    private File assetRoot;
    public File getAssetRoot() {
        return assetRoot;
    }
    private File archiveDir;
    private File getArchiveDir() {
        return archiveDir;
    }

    private static File gitRoot;
    private static File getGitRoot() {
        if (gitRoot == null) {
            String prop = PropertyManager.getProperty(PropertyNames.MDW_GIT_LOCAL_PATH);
            if (prop != null)
              gitRoot = new File(prop);
        }
        return gitRoot;
    }
    /**
     * from mdw.properties (not necessarily actual branch for switch scenario)
     */
    private static String getGitBranch() {
        return PropertyManager.getProperty(PropertyNames.MDW_GIT_BRANCH);
    }

    private static String getGitRemoteUrl() {
        return PropertyManager.getProperty(PropertyNames.MDW_GIT_REMOTE_URL);
    }

    private static String getGitUser() {
        return PropertyManager.getProperty(PropertyNames.MDW_GIT_USER);
    }
    /**
     * Relative to gitRoot
     */
    private static String assetPath;
    private String getAssetPath() throws IOException {
        if (assetPath == null && getVersionControlGit() != null) {
            assetPath = getVersionControlGit().getRelativePath(assetRoot);
        }
        return assetPath;
    };

    public AssetServicesImpl() {
        assetRoot = ApplicationContext.getAssetRoot();
        archiveDir = new File(assetRoot + "/" + PackageDir.ARCHIVE_SUBDIR);
    }

    public VersionControl getVersionControl() throws IOException {
        return getVersionControlGit();
    }

    private VersionControlGit getVersionControlGit() throws IOException  {
        try {
            return (VersionControlGit)DataAccess.getAssetVersionControl(assetRoot);
        }
        catch (DataAccessException ex) {
            if (ex.getCause() instanceof IOException) {
                logger.severeException(ex.getMessage(), ex);
                throw (IOException)ex.getCause();
            }
            else {
                throw new IOException(ex.getMessage(), ex);
            }
        }
    }

    /**
     * Returns all the assets for the specified package.
     * Works only for VCS assets.  Does not use the AssetVOCache.
     */
    public PackageAssets getAssets(String packageName) throws ServiceException {

        try {
            PackageDir pkgDir = getPackageDir(packageName);
            if (pkgDir == null) {
                pkgDir = getGhostPackage(packageName);
                if (pkgDir == null)
                    throw new DataAccessException("Missing package metadata directory: " + pkgDir);
            }
            List<AssetInfo> assets = new ArrayList<AssetInfo>();
            if (!DiffType.MISSING.equals(pkgDir.getVcsDiffType())) {
                for (File file : pkgDir.listFiles())
                    if (file.isFile()) {
                        assets.add(new AssetInfo(pkgDir.getAssetFile(file)));
                }
            }

            PackageAssets pkgAssets = new PackageAssets(pkgDir);
            pkgAssets.setAssets(assets);

            CodeTimer timer = new CodeTimer("AssetServices", true);
            addVersionControlInfo(pkgAssets);
            pkgAssets.sort();
            timer.stopAndLogTiming("addVersionControlInfo(PackageAssets)");

            return pkgAssets;
        }
        catch (Exception ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
    }

    public Map<String,List<AssetInfo>> getAssetsOfType(String format) throws ServiceException {
        Map<String,List<AssetInfo>> packageAssets = new HashMap<String,List<AssetInfo>>();
        List<File> assetRoots = new ArrayList<File>();
        assetRoots.add(assetRoot);
        try {
            CodeTimer timer = new CodeTimer("AssetServices", true);
            List<PackageDir> pkgDirs = findPackageDirs(assetRoots);
            for (PackageDir pkgDir : pkgDirs) {
                List<AssetInfo> assets = null;
                for (File file : pkgDir.listFiles())
                    if (file.isFile() && file.getName().endsWith("." + format)) {
                        if (assets == null)
                            assets = new ArrayList<AssetInfo>();
                        assets.add(new AssetInfo(pkgDir.getAssetFile(file)));
                }
                if (assets != null)
                    packageAssets.put(pkgDir.getPackageName(), assets);
            }
            timer.logTimingAndContinue("getAssetsOfType(" + format + ")");
            return packageAssets;
        }
        catch (DataAccessException ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
        catch (IOException ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
    }

    public PackageList getPackages(boolean withVcsInfo) throws ServiceException {
        List<File> assetRoots = new ArrayList<File>();
        assetRoots.add(assetRoot);
        try {
            File vcsRoot = getVersionControl() == null ? null : getGitRoot();
            PackageList pkgList = new PackageList(ApplicationContext.getServerHostPort(), assetRoot, vcsRoot);
            CodeTimer timer = new CodeTimer("AssetServices", true);
            List<PackageDir> pkgDirs = findPackageDirs(assetRoots);
            timer.logTimingAndContinue("findPackageDirs()");
            pkgList.setPackageDirs(pkgDirs);
            if (withVcsInfo)
              addVersionControlInfo(pkgList);
            pkgList.sort();
            timer.stopAndLogTiming("addVersionControlInfo(PackageList)");
            return pkgList;
        }
        catch (DataAccessException ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, ex.getMessage(), ex);
        }
        catch (IOException ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, ex.getMessage(), ex);
        }
    }

    public AssetPackageList getAssetPackageList(Query query) throws ServiceException {
        try {
            List<PackageAssets> packageAssetList = new ArrayList<>();
            for (PackageDir pkgDir : findPackageDirs(Arrays.asList(new File[]{assetRoot}))) {

                Stream<File> stream = Arrays.asList(pkgDir.listFiles()).stream();

                // currently "extension" is the only supported filter
                String ext = query.getFilter("extension");
                if (ext != null) {
                    stream = stream.filter(f -> f.isFile() && f.getName().endsWith("." + ext));
                }

                List<AssetInfo> assets = new ArrayList<AssetInfo>();
                for (File file : stream.collect(Collectors.toList())) {
                    assets.add(new AssetInfo(pkgDir.getAssetFile(file)));
                };
                if (!assets.isEmpty()) {
                    PackageAssets pkgAssets = new PackageAssets(pkgDir);
                    pkgAssets.setAssets(assets);
                    packageAssetList.add(pkgAssets);
                }
            }
            AssetPackageList assetPackageList = new AssetPackageList(packageAssetList);
            assetPackageList.setRetrieveDate(DatabaseAccess.getDbDate());
            assetPackageList.sort();
            return assetPackageList;
        }
        catch (DataAccessException ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, ex.getMessage(), ex);
        }
        catch (IOException ex) {
            throw new ServiceException(ServiceException.INTERNAL_ERROR, ex.getMessage(), ex);
        }
    }

    public PackageDir getPackage(String name) throws ServiceException {
        try {
            return getPackageDir(name);
        }
        catch (IOException ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
        catch (DataAccessException ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
    }
    /**
     * Finds the next level of sibling PackageDirs under a set of non-package dirs.
     */
    private List<PackageDir> findPackageDirs(List<File> dirs) throws IOException, DataAccessException {
        List<PackageDir> pkgSubDirs = new ArrayList<PackageDir>();
        List<File> allSubDirs = new ArrayList<File>();

        for (File dir : dirs) {
            for (File sub : dir.listFiles()) {
                if (sub.isDirectory() && !sub.equals(getArchiveDir())) {
                    if (new File(sub + "/.mdw").isDirectory()) {
                        PackageDir pkgSubDir = new PackageDir(getAssetRoot(), sub, getAssetVersionControl());
                        pkgSubDir.parse();
                        pkgSubDirs.add(pkgSubDir);
                    }
                    allSubDirs.add(sub);
                }
            }
        }

        if (!allSubDirs.isEmpty())
            pkgSubDirs.addAll(findPackageDirs(allSubDirs));

        return pkgSubDirs;
    }

    private void addVersionControlInfo(PackageList pkgList) throws ServiceException {
        try {
            VersionControlGit versionControl = (VersionControlGit) getVersionControl();
            if (versionControl != null) {
                if (getGitBranch() != null)
                    pkgList.setVcsBranch(getGitBranch());
                if (getGitRemoteUrl() != null)
                    pkgList.setVcsRemoteUrl(getGitRemoteUrl());

                pkgList.setGitBranch(versionControl.getBranch());

                if (versionControl != null && !pkgList.getPackageDirs().isEmpty() && getGitUser() != null) {
                    GitDiffs diffs = versionControl.getDiffs(getGitBranch(), getAssetPath());

                    for (PackageDir pkgDir : pkgList.getPackageDirs()) {
                        String pkgVcPath = versionControl.getRelativePath(pkgDir);
                        // check for extra packages
                        for (String extraPath : diffs.getDiffs(DiffType.EXTRA)) {
                            if (extraPath.equals(pkgVcPath + "/" + PackageDir.PACKAGE_JSON_PATH)) {
                                pkgDir.setVcsDiffType(DiffType.EXTRA);
                                break;
                            }
                        }
                        if (pkgDir.getVcsDiffType() == null) {
                            // check for packages with sub-content changes
                            for (DiffType diffType : DiffType.values()) {
                                if (pkgDir.getVcsDiffType() == null) { // not already found different
                                    for (String diff : diffs.getDiffs(diffType)) {
                                        if (diff.startsWith(pkgVcPath + "/") && !diff.startsWith(pkgVcPath + "/.mdw")) {
                                            pkgDir.setVcsDiffType(DiffType.DIFFERENT);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // check for missing packages
                    for (String missingDiff : diffs.getDiffs(DiffType.MISSING)) {
                        if (missingDiff.endsWith(PackageDir.PACKAGE_JSON_PATH)) {
                            int trim = PackageDir.PACKAGE_JSON_PATH.length();
                            // add a ghost package
                            String pkgName = missingDiff.substring(getAssetPath().length() + 1, missingDiff.length() - trim - 1).replace('/', '.');
                            PackageDir pkgDir = getGhostPackage(pkgName);
                            if (pkgDir != null)
                                pkgList.getPackageDirs().add(pkgDir);
                        }
                    }
                }
            }
        }
        catch (Exception ex) {
            logger.severeException("Unable to retrieve Git information for asset packages", ex);
        }
    }

    private void addVersionControlInfo(PackageAssets pkgAssets) throws ServiceException {
        try {
            VersionControlGit versionControl = (VersionControlGit) getVersionControl();
            if (versionControl != null && getGitUser() != null) {
                PackageDir pkgDir = pkgAssets.getPackageDir();
                String pkgVcPath = versionControl.getRelativePath(pkgDir);
                GitDiffs diffs = versionControl.getDiffs(getGitBranch(), pkgVcPath);
                pkgDir.setVcsDiffType(diffs.getDiffType(pkgVcPath + "/" + PackageDir.META_DIR + "/" + pkgDir.getMetaFile().getName()));

                for (AssetInfo asset : pkgAssets.getAssets()) {
                    String assetVcPath = versionControl.getRelativePath(asset.getFile());
                    DiffType diffType = diffs.getDiffType(assetVcPath);
                    if (diffType != null) {
                        asset.setVcsDiffType(diffType);
                        if (pkgDir.getVcsDiffType() == null)
                          pkgDir.setVcsDiffType(DiffType.DIFFERENT);
                    }
                }

                // check for missing assets
                for (String missingDiff : diffs.getDiffs(DiffType.MISSING)) {
                    if (missingDiff.startsWith(pkgVcPath + "/") && !missingDiff.startsWith(pkgVcPath + "/.mdw")) {
                        String assetName = missingDiff.substring(pkgVcPath.length() + 1);
                        AssetInfo asset = getGhostAsset(pkgDir, assetName);
                        if (asset != null)
                          pkgAssets.getAssets().add(asset);
                    }
                }
            }
        }
        catch (Exception ex) {
            logger.severeException("Unable to retrieve Git information for asset packages", ex);
        }
    }

    public PackageDir getPackageDir(String name) throws IOException, DataAccessException {
        File dir = new File(assetRoot + "/" + name.replace('.', '/'));
        if (new File(dir + "/.mdw").isDirectory()) {
            PackageDir pkgDir = new PackageDir(assetRoot, dir, getAssetVersionControl());
            pkgDir.parse();
            return pkgDir;
        }
        else {
            return null;
        }
    }

    /**
     * Falls back to DataAccess version control for asset versioning.
     */
    private VersionControl getAssetVersionControl() throws IOException, DataAccessException {
        VersionControl vc = getVersionControl();
        if (vc == null)
            vc = DataAccess.getAssetVersionControl(assetRoot);
        return vc;
    }

    public AssetInfo getAsset(String path) throws ServiceException {
        try {
            int lastSlash = path.lastIndexOf('/');
            String pkgName = path.substring(0, lastSlash);
            String assetName = path.substring(lastSlash + 1);
            PackageDir pkgDir = getPackageDir(pkgName);
            if (pkgDir == null) {
                pkgDir = getGhostPackage(pkgName);
                if (pkgDir == null)
                    throw new DataAccessException("Missing package metadata directory: " + pkgDir);

                // ghost package contains ghost assets
                AssetInfo asset = getGhostAsset(pkgDir, assetName);
                if (asset == null)
                    throw new DataAccessException("Missing asset file: " + path);
                return asset;
            }
            AssetFile assetFile = pkgDir.getAssetFile(new File(pkgDir + "/" + assetName));
            if (assetFile.isFile()) {
                AssetInfo asset = new AssetInfo(assetFile);
                addVersionControlInfo(asset);
                return asset;
            }
            else {
                AssetInfo asset = getGhostAsset(pkgDir, assetName);
                if (asset == null)
                    throw new DataAccessException("Missing asset file for path: " + getAssetPath());
                return asset;
            }
        }
        catch (Exception ex) {
            throw new ServiceException(ex.getMessage(), ex);
        }
    }

    private void addVersionControlInfo(AssetInfo asset) throws ServiceException {
        CodeTimer timer = new CodeTimer("addVersionControlInfo(AssetInfo)", true);
        try {
            VersionControlGit versionControl = (VersionControlGit) getVersionControl();
            if (versionControl != null && getGitUser() != null) {
                String assetVcPath = versionControl.getRelativePath(asset.getFile());
                asset.setCommitInfo(versionControl.getCommitInfo(assetVcPath));
                GitDiffs diffs = versionControl.getDiffs(getGitBranch(), assetVcPath);
                asset.setVcsDiffType(diffs.getDiffType(assetVcPath));
            }
        }
        catch (Exception ex) {
            logger.severeException("Unable to retrieve Git information for asset packages", ex);
        }
        finally {
            timer.stopAndLogTiming(null);
        }
    }

    private PackageDir getGhostPackage(String pkgName) throws Exception {
        PackageDir pkgDir = null;
        String pkgPath = pkgName.replace('.', '/');
        VersionControlGit gitVc = (VersionControlGit) getVersionControl();
        if (gitVc != null && getGitUser() != null) {
            String pkgMetaFilePath = getAssetPath() + "/" + pkgPath + "/" + PackageDir.PACKAGE_JSON_PATH;
            GitDiffs diffs = gitVc.getDiffs(getGitBranch(), pkgMetaFilePath);
            if (DiffType.MISSING.equals(diffs.getDiffType(pkgMetaFilePath))) {
                VersionControl vc = null;
                String versionFilePath = getAssetPath() + "/" + pkgPath + "/" + PackageDir.VERSIONS_PATH;
                String versionPropsStr = gitVc.getRemoteContentString(getGitBranch(), versionFilePath);
                if (versionPropsStr != null) {
                    Properties versionProps = new Properties();
                    versionProps.load(new ByteArrayInputStream(versionPropsStr.getBytes()));
                    vc = new GhostVersionControl(versionProps);
                }
                pkgDir = new PackageDir(assetRoot, pkgName, vc);
                pkgDir.setVcsDiffType(DiffType.MISSING);
                String metaContent = ((VersionControlGit)getVersionControl()).getRemoteContentString(getGitBranch(), pkgMetaFilePath);
                if (metaContent != null) {
                    if (metaContent.trim().startsWith("{")) {
                        Package pkgVO = new Package(new JSONObject(metaContent));
                        pkgDir.setPackageVersion(pkgVO.getVersionString());
                    }
                    else {
                        PackageDocument pkgDoc = PackageDocument.Factory.parse(metaContent);
                        pkgDir.setPackageVersion(pkgDoc.getPackage().getVersion());
                    }
                }
            }
            else {
                throw new IOException("Cannot locate missing package XML in version control: " + pkgMetaFilePath);
            }
        }
        return pkgDir;
    }

    private AssetInfo getGhostAsset(PackageDir pkgDir, String assetName) throws Exception {
        AssetInfo asset = null;
        VersionControlGit gitVc = (VersionControlGit) getVersionControl();
        if (gitVc != null && getGitUser() != null) {
            String path = getAssetPath() + "/" + pkgDir.getPackageName() + "/" + assetName;
            GitDiffs diffs = gitVc.getDiffs(getGitBranch(), path);
            if (DiffType.MISSING.equals(diffs.getDiffType(path))) {
                AssetFile assetFile = pkgDir.getAssetFile(new File(path));
                asset = new AssetInfo(assetFile);
                asset.setVcsDiffType(DiffType.MISSING);
                if (pkgDir.getVcsDiffType() != DiffType.MISSING) {
                    // non-ghost pkg -- set version from remote
                    String versionFilePath = getAssetPath() + "/" + pkgDir.getPackageName() + "/" + PackageDir.VERSIONS_PATH;
                    String versionPropsStr = gitVc.getRemoteContentString(getGitBranch(), versionFilePath);
                    if (versionPropsStr != null) {
                        Properties versionProps = new Properties();
                        versionProps.load(new ByteArrayInputStream(versionPropsStr.getBytes()));
                        String val = versionProps.getProperty(assetName);
                        if (val != null)
                            assetFile.setRevision(VersionControlGit.parseAssetRevision(val.trim()));
                    }
                }
            }
            else {
                logger.warn("Cannot locate missing asset in version control: " + pkgDir.getPackageName() + "/" + assetName);
            }
        }
        return asset;
    }

    private class GhostVersionControl implements VersionControl {

        private Properties assetRevisions;

        GhostVersionControl(Properties assetRevisions) {
            this.assetRevisions = assetRevisions;
        }

        public AssetRevision getRevision(File file) throws IOException {
            if (assetRevisions == null)
                return null;
            String val = assetRevisions.getProperty(file.getName());
            if (val == null)
                return null;
            return VersionControlGit.parseAssetRevision(val.trim());
        }

        public void connect(String repositoryUrl, String user, String password, File localDir) throws IOException {
        }

        public long getId(File file) throws IOException {
            return 0;
        }

        public File getFile(long id) {
            return null;
        }

        public void setRevision(File file, AssetRevision rev) throws IOException {
        }

        public void clearId(File file) {
        }

        public void deleteRev(File file) throws IOException {
        }

        public void clear() {
        }
    }
}
