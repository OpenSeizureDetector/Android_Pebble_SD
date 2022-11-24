package uk.org.openseizuredetector;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;

import fi.iki.elonen.NanoHTTPD;


/**
 * Class describing the seizure detector web server - appears on port
 * 8080.
 */
public class SdWebServer extends NanoHTTPD {
    private String TAG = "WebServer";
    private SdData mSdData;
    private SdServer mSdServer;
    private Context mContext;
    private OsdUtil mUtil;

    public SdWebServer(Context context, SdData sdData, SdServer sdServer) {
        // Set the port to listen on (8080)
        super(8080);
        mSdData = sdData;
        mContext = context;
        mSdServer = sdServer;
        Handler mHandler = new Handler();
        mUtil = new OsdUtil(mContext, mHandler);
    }

    public void setSdData(SdData sdData) {
        // Log.v(TAG, "setSdData()");
        mSdData = sdData;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri;
        Method method;
        Map<String, String> header;
        Map<String, String> parameters;
        Map<String, String> files = new HashMap<>();
        NanoHTTPD.Response res = null;
        String responseMimeType = "application/json";

        if (session.getMethod() == Method.POST) {
            // We try to parse the 'files' part of POST requests to get the data
            try {
                session.parseBody(files);
            } catch (IOException ioe) {
                Log.e(TAG, "IOError parsing body of request");
                return new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
            } catch (ResponseException re) {
                Log.e(TAG, "ResponseException parsing body of request" + re.getMessage());
                return new Response(re.getStatus(), MIME_PLAINTEXT, re.getMessage());
            }
        }
        uri = session.getUri();
        method = session.getMethod();
        header = session.getHeaders();
        parameters = session.getParms();

        Log.v(TAG, "WebServer.serve() - uri=" + uri + " Method=" + method.toString());
        String answer = "{'msg': 'Error - you should not see this message! - Something wrong in WebServer.serve()'}";

        Iterator it = parameters.keySet().iterator();
        while (it.hasNext()) {
            Object key = it.next();
            Object value = parameters.get(key);
            //Log.v(TAG,"Request parameters - key="+key+" value="+value);
        }

        if (uri.equals("/")) uri = "/index.html";
        switch (uri) {
            case "/data":
                switch (method) {
                    case GET:
                        //Log.v(TAG,"WebServer.serve() - Returning data");
                        try {
                            Log.v(TAG, "WebServer.serve() - GET /data - sending " + mSdData.toString());
                            answer = mSdData.toString();
                        } catch (Exception ex) {
                            Log.v(TAG, "Error Creating Data Object - " + ex.toString());
                            answer = "{'msg': 'Error Creating Data Object'}";
                        }
                        break;
                    case POST:
                        Log.v(TAG, "WebServer.serve() - POST /data - receiving data from device: parameters=" + parameters.toString());
                        Log.v(TAG, "              header=" + header.toString());
                        Log.v(TAG, "              files=" + files.toString());
                        String postData = files.get("postData");
                        Log.v(TAG, "              postData=" + postData);
                        if (mSdServer.mSdDataSourceName.equals("Garmin")) {
                            // Send the data to the SdDataSource so the app can pick it up.
                            if (parameters.get("dataObj") != null) {
                                Log.v(TAG, "passing parameters to data source");
                                answer = mSdServer.mSdDataSource.updateFromJSON(parameters.get("dataObj").toString());
                            } else {
                                Log.v(TAG, "Passing postData to data source");
                                answer = mSdServer.mSdDataSource.updateFromJSON(files.get("postData"));
                            }
                        } else {
                            Log.i(TAG,"Web server received data, but datasource is not set to 'Garmin' - Ignoring");
                            mUtil.showToast("Web server received data, but datasource is not set to 'Garmin' - Ignoring");
                        }
                        break;
                    default:
                        Log.v(TAG, "WebServer.serve() - Unrecognised method - " + method);
                }
                break;

            case "/settings":
                switch (method) {
                    case GET:
                        //Log.v(TAG,"WebServer.serve() - Returning settings");
                        try {
                            JSONObject jsonObj = new JSONObject();
                            jsonObj.put("alarmFreqMin", mSdData.alarmFreqMin);
                            jsonObj.put("alarmFreqMax", mSdData.alarmFreqMax);
                            jsonObj.put("nMin", mSdData.nMin);
                            jsonObj.put("nMax", mSdData.nMax);
                            jsonObj.put("warnTime", mSdData.warnTime);
                            jsonObj.put("alarmTime", mSdData.alarmTime);
                            jsonObj.put("alarmThresh", mSdData.alarmThresh);
                            jsonObj.put("alarmRatioThresh", mSdData.alarmRatioThresh);
                            jsonObj.put("batteryPc", mSdData.batteryPc);
                            answer = jsonObj.toString();
                        } catch (Exception ex) {
                            Log.v(TAG, "Error Creating Data Object - " + ex.toString());
                            answer = "{'msg': 'Error Creating Data Object'}";
                        }
                        break;
                    case POST:
                        Log.v(TAG, "WebServer.serve() - POST /settings - receiving data from device: parameters=" + parameters.toString());
                        Log.v(TAG, "              header=" + header.toString());
                        Log.v(TAG, "              files=" + files.toString());
                        // Send the data to the SdDataSource so the app can pick it up.
                        if (parameters != null) {
                            Log.v(TAG,"passing parameters to data source");
                            answer = mSdServer.mSdDataSource.updateFromJSON(parameters.get("dataObj").toString());
                        } else {
                            Log.v(TAG,"Passing postData to data source");
                            answer = mSdServer.mSdDataSource.updateFromJSON(files.get("postData"));
                        }
                        //mSdServer.mSdDataSource.updateFromJSON(parameters.toString());
                        break;
                    default:
                        Log.v(TAG, "WebServer.serve() - Unrecognised method - " + method);
                }
                break;
            case "/spectrum":
                Log.v(TAG, "WebServer.serve() - Returning spectrum - 1");
                try {
                    JSONObject jsonObj = new JSONObject();
                    Log.v(TAG, "WebServer.serve() - Returning spectrum - 2");
                    // Initialised it this way because one phone was ok with JSONArray(mSdData.simpleSpec), and the other crashed...
                    JSONArray arr = new JSONArray();
                    for (int i = 0; i < mSdData.simpleSpec.length; i++) {
                        arr.put(mSdData.simpleSpec[i]);
                    }

                    Log.v(TAG, "WebServer.serve() - Returning spectrum - 3");
                    jsonObj.put("simpleSpec", arr);
                    Log.v(TAG, "WebServer.serve() - Returning spectrum - 4");
                    answer = jsonObj.toString();
                    Log.v(TAG, "WebServer.serve() - Returning spectrum - 5" + answer);
                } catch (Exception ex) {
                    Log.v(TAG, "Error Creating Data Object - " + ex.toString());
                    answer = "{'msg' : 'Error Creating Data Object'}";
                }
                break;

            case "/acceptalarm":
                Log.v(TAG, "WebServer.serve() - Accepting alarm");
                mSdServer.acceptAlarm();
                answer = "{'msg' : 'Alarm Accepted'}";
                break;

            default:
                if (uri.startsWith("/index.html") ||
                        uri.startsWith("/logfiles.html") ||
                        uri.startsWith("/favicon.ico") ||
                        uri.startsWith("/js/") ||
                        uri.startsWith("/css/") ||
                        uri.startsWith("/img/")) {
                    //Log.v(TAG,"Serving File");
                    return serveFile(uri);
                } else if (uri.startsWith("/logs")) {
                    Log.v(TAG, "WebServer.serve() - serving data logs - uri=" + uri);
                    NanoHTTPD.Response resp = serveLogFile(uri);
                    Log.v(TAG, "WebServer.serve() - response = " + resp.toString());
                    return resp;
                } else {
                    Log.v(TAG, "WebServer.serve() - Unknown uri -" +
                            uri);
                    answer = "{'msg' : 'Unknown URI: "+uri+"'}";
                }
        }
        res = new NanoHTTPD.Response(answer);
        res.setMimeType(responseMimeType);
        Log.v(TAG,"WebServer.serve() - returning "+res.getData()+", mime="+res.getMimeType()+", status="+res.getStatus());
        return (res);
    }


    /**
     * Return a file from the external storage folder
     */
    NanoHTTPD.Response serveLogFile(String uri) {
        NanoHTTPD.Response res;
        InputStream ip = null;
        String uripart;
        Log.v(TAG, "serveLogFile(" + uri + ")");
        try {
            if (ip != null) ip.close();
            StringTokenizer uriParts = new StringTokenizer(uri, "/");
            Log.v(TAG, "serveExternalFile - number of tokens = " + uriParts.countTokens());
            while (uriParts.hasMoreTokens()) {
                uripart = uriParts.nextToken();
                Log.v(TAG, "uripart=" + uripart);
            }

            // If we have only given a "/logs" URI, return a list of
            // available files.
            // Re-start the StringTokenizer from the start.
            uriParts = new StringTokenizer(uri, "/");
            Log.v(TAG, "serveExternalFile - number of tokens = "
                    + uriParts.countTokens());
            if (uriParts.countTokens() == 1) {
                Log.v(TAG, "Returning list of files");

                try {
                    JSONObject jsonObj = new JSONObject();
                    File[] fileList = mUtil.getDataFilesList();
                    Log.v(TAG, "serveLogFile(): fileList=" + fileList.toString()+", length="+fileList.length);
                    JSONArray arr = new JSONArray();
                        for (int i = 0; i < fileList.length; i++) {
                            Log.v(TAG, "serveLogFile(): file[" + i + "]=" + fileList[i]);
                            arr.put(fileList[i].getName());
                        }
                        jsonObj.put("logFileList", arr);
                    res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
                            "text/html", jsonObj.toString());
                } catch (Exception ex) {
                    res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
                            "text/html", "ERROR - " + ex.toString());
                }
                return res;
            }

            uripart = uriParts.nextToken();  // This will just be /logs
            uripart = uriParts.nextToken();  // this is the requested file.
            String fname = mUtil.getDataStorageDir().toString() + "/" + uripart;
            Log.v(TAG, "serveLogFile - uri=" + uri + ", fname=" + fname);
            ip = new FileInputStream(fname);
            String mimeStr = "text/html";
            res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
                    mimeStr, ip);
            res.addHeader("Content-Length", "" + ip.available());
        } catch (IOException ex) {
            Log.v(TAG, "serveLogFile(): Error Opening File - " + ex.toString());
            res = new NanoHTTPD.Response("serveLogFile(): Error Opening file " + uri);
        }
        return (res);
    }

    /**
     * Return a file from the apps /assets folder
     */
    NanoHTTPD.Response serveFile(String uri) {
        NanoHTTPD.Response res;
        InputStream ip = null;
        try {
            if (ip != null) ip.close();
            String assetPath = "www";
            String fname = assetPath + uri;
            //Log.v(TAG,"serveFile - uri="+uri+", fname="+fname);
            AssetManager assetManager = mContext.getResources().getAssets();
            ip = assetManager.open(fname);
            String mimeStr = "text/html";
            res = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK,
                    mimeStr, ip);
            res.addHeader("Content-Length", "" + ip.available());
        } catch (IOException ex) {
            Log.v(TAG, "serveFile(): Error Opening File - " + ex.toString());
            res = new NanoHTTPD.Response("serveFile(): Error Opening file " + uri);
        }
        return (res);
    }


}
