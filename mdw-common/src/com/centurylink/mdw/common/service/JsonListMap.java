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
package com.centurylink.mdw.common.service;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Used to make instance counts Jsonable.
 */
public class JsonListMap<T extends Jsonable> implements Jsonable {

    private Map<String,List<T>> jsonables;
    public Map<String,List<T>> getJsonables() { return jsonables; }

    public JsonListMap(Map<String,List<T>> jsonables) {
        this.jsonables = jsonables;
    }

    public JsonListMap(String name, Map<String,List<T>> jsonables) {
        this.name = name;
        this.jsonables = jsonables;
    }

    public JsonListMap(JSONObject json, Class<T> type) throws JSONException {
        this.jsonables = new HashMap<String,List<T>>();
        for (String name : JSONObject.getNames(json)) {
            JSONArray jsonArr = json.getJSONArray(name);
            List<T> list = new ArrayList<T>();
            for (int i = 0; i < jsonArr.length(); i++) {
                JSONObject jsonObj = jsonArr.getJSONObject(i);
                try {
                    Constructor<T> c = type.getConstructor(JSONObject.class);
                    T jsonable = c.newInstance(jsonObj);
                    list.add(jsonable);
                }
                catch (Exception ex) {
                    throw new JSONException(ex);
                }
            }
            jsonables.put(name, list);
        }
    }

    public JSONObject getJson() throws JSONException {

        JSONObject json = new JSONObject();
        for (String key : jsonables.keySet()) {
            JSONArray jsonArr = new JSONArray();
            for (Jsonable jsonable : jsonables.get(key)) {
                jsonArr.put(jsonable.getJson());
            }
            json.put(key, jsonArr);
        }
        return json;
    }

    private String name;
    public String getJsonName() {
        return name;
    }
}
