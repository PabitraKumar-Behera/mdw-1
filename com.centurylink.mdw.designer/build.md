Steps for Building and Publishing MDW Designer Plug-In:
-------------------------------------------------------

In com.centurylink.mdw.designer:
 - Update mdwDesignerVersion in gradle.properties

(If changed) Export tutorial docx files to HTML in MDW 5.5 mdw-hub/web/doc/tutorials.
In com.centurylink.mdw.designer.ui:
 - Run the Gradle build task copyTutorialDocs (copies into com.centurylink.mdw.designer.ui/help/doc/tutorials)

Commit and push these changes to Git remote

Perform Jenkins Builds (http://lxdenvmtc143.dev.qintra.com:8181/jenkins):
 - MDW55 - Build
 - Designer Build
 - Designer Publish (or Designer Preview)
    
Commit updateSite files to mdw6/docs/designer/updateSite on GitHub (and remove old ones).    
(Other plugins required in com.centurylink.mdw.designer.feature/feature.xml should already be present.
 If versions have changed, the newer versions may need to be uploaded (esp. cucumber.eclipse).
    
Test updating Eclipse (Mars/Neon) to the new build.
  Update Site URL: http://centurylinkcloud.github.io/mdw/designer/updateSite

(If RCP is to be included in this build)
Build com.centurylink.mdw.designer.rcp according to the instructions in its build_readme.txt.

Publish Release Notes to the MDW Users mailing list.
  View the Git revision history (in workspace dir):
  git log  --name-status --abbrev-commit --after={2017-01-08} com.centurylink.mdw.designer com.centurylink.mdw.designer.core com.centurylink.mdw.designer.ui com.centurylink.mdw.designer.feature com.centurylink.mdw.designer.rcp > notes.txt
  Use this information to produce the release notes email.
  
Upload the release notes email to the SharePoint site:
  - Select the release notes email in Outlook and from the menu select File > Save As > Save As Type = Text Only.
  - Filename format is MDW Designer Build x.x.x.txt.
  - Upload to SharePoint under Releases > Release Notes > MDW Designer.
  
On GitHub:
  - Close any open issues delivered with this build.
  - Create a milestone marker for the next upcoming build.
  - Assign any undelivered issues for this build's milestone to the next build's milestone.
  - Close this build's milestone in GitHub. 

Update support items delivered with this build to Resolved status.

