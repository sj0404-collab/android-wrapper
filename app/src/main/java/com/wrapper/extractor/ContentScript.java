package com.wrapper.extractor;

import org.json.JSONArray;

/**
 * Content script из расширения
 */
public class ContentScript {
    private JSONArray matches;
    private JSONArray js;
    private JSONArray css;
    private String runAt;

    public JSONArray getMatches() { return matches; }
    public void setMatches(JSONArray matches) { this.matches = matches; }

    public JSONArray getJs() { return js; }
    public void setJs(JSONArray js) { this.js = js; }

    public JSONArray getCss() { return css; }
    public void setCss(JSONArray css) { this.css = css; }

    public String getRunAt() { return runAt; }
    public void setRunAt(String runAt) { this.runAt = runAt; }
}
