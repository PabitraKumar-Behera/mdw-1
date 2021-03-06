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
package com.centurylink.mdw.model.asset;

import java.util.Date;

import org.json.JSONException;
import org.json.JSONObject;

import com.centurylink.mdw.common.service.Jsonable;
import com.centurylink.mdw.util.StringHelper;

public class CommitInfo implements Jsonable {

    private String commit;
    public String getCommit() { return commit; }

    private String committer;
    public String getCommitter() { return committer; }
    public void setCommitter(String committer) { this.committer = committer; }

    private String email;
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    private Date date;
    public Date getDate() { return date; }
    public void setDate(Date date) { this.date = date; }

    private String message;
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public CommitInfo(String commit) {
        this.commit = commit;
    }

    public CommitInfo(JSONObject json) throws JSONException {
        if (json.has("commit"))
            this.commit = json.getString("commit");
        if (json.has("committer"))
            this.committer = json.getString("committer");
        if (json.has("email"))
            this.email = json.getString("email");
        if (json.has("date"))
            this.date = StringHelper.stringToDate(json.getString("date"));
        if (json.has("message"))
            this.message = json.getString("message");
    }

    public JSONObject getJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (commit != null)
            json.put("commit", commit);
        if (committer != null)
            json.put("committer", committer);
        if (email != null)
            json.put("email", email);
        if (date != null)
            json.put("date", StringHelper.dateToString(date));
        if (message != null)
            json.put("message", message);
        return json;
    }

    public String getJsonName() {
        return "commitInfo";
    }
}
