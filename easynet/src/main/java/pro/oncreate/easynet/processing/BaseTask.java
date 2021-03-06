package pro.oncreate.easynet.processing;

import android.os.AsyncTask;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import pro.oncreate.easynet.EasyNet;
import pro.oncreate.easynet.data.NConst;
import pro.oncreate.easynet.data.NErrors;
import pro.oncreate.easynet.methods.QueryMethod;
import pro.oncreate.easynet.models.NRequestModel;
import pro.oncreate.easynet.models.NResponseModel;
import pro.oncreate.easynet.models.subsidiary.NKeyValueModel;
import pro.oncreate.easynet.utils.NDataBuilder;
import pro.oncreate.easynet.utils.NLog;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE;


/**
 * Copyright (c) $today.year. Konovalenko Andrii [jaksab2@mail.ru]
 */

@SuppressWarnings("unused,WeakerAccess")
public abstract class BaseTask extends AsyncTask<String, Integer, NResponseModel> {


    //
    // Data
    //

    public final static int DEFAULT_TIMEOUT_READ = 10000;
    public final static int DEFAULT_TIMEOUT_CONNECT = 7500;

    protected static final int BUFFER_SIZE = 8192;
    protected static final String charset = "UTF-8";

    protected String tag;
    protected OutputStream outputStream;
    protected PrintWriter writer;
    protected BaseTask.NTaskListener listener;
    protected NRequestModel requestModel;


    //
    // 
    //

    public BaseTask(BaseTask.NTaskListener listener, NRequestModel requestModel) {
        this.listener = listener;
        this.requestModel = requestModel;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        if (listener != null)
            listener.start(requestModel);
        requestModel.setStartTime(System.currentTimeMillis());
        setTag(requestModel.getTag());
        EasyNet.getInstance().addTask(this.tag, this);
    }

    @Override
    protected NResponseModel doInBackground(String... params) {
        Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND + THREAD_PRIORITY_MORE_FAVORABLE);

        NResponseModel responseModel;
        HttpURLConnection connection = null;
        String body;
        InputStream inputStream;

        try {
            NLog.logD("======== START REQUEST ========");
            NLog.logD(String.format(Locale.getDefault(), "[%s] %s", requestModel.getMethod(), requestModel.getUrl()));

            while (true) {
                addUrlParams();
                connection = setupConnection();
                addHeaders(connection);
                makeRequestBody(connection);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    String newUrl = connection.getHeaderField("Location");

                    boolean next = true;
                    if (listener != null)
                        next = listener.redirect(newUrl);

                    if (!next) {
                        NLog.logE("[The redirect is forbidden]: " + newUrl);
                        responseModel = new NResponseModel(requestModel.getUrl(), responseCode,
                                null, null);
                        responseModel.setRedirectInterrupted(true);
                        responseModel.setRedirectLocation(newUrl);
                        break;
                    } else {
                        NLog.logD("[Redirect]: " + newUrl);
                        requestModel.setUrl(newUrl);
                        requestModel.clearParams();
                        requestModel.setRequestType(NConst.MIME_TYPE_X_WWW_FORM_URLENCODED);
                        connection.disconnect();
                    }
                } else {
                    NLog.logD("[Status code]: " + responseCode);

                    inputStream = getInputStreamFromConnection(connection);
                    body = readResponseBody(inputStream);
                    Map<String, List<String>> headers = getResponseHeaders(connection, body);

                    responseModel = new NResponseModel(requestModel.getUrl(), responseCode, body, headers);
                    responseModel.setEndTime(System.currentTimeMillis());
                    responseModel.setResponseTime((int) (responseModel.getEndTime() - requestModel.getStartTime()));

                    NLog.logD("[Response time]: " + responseModel.getResponseTime() + " ms");

                    if (listener != null)
                        listener.finish(responseModel);
                    break;
                }
            }
        } catch (Exception e) {
            responseModel = null;
            NLog.logD("[Error]: " + e.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            EasyNet.getInstance().removeTask(getTag());
        }
        return responseModel;
    }

    @Override
    protected void onProgressUpdate(Integer... errorCode) {
    }

    @Override
    protected void onPostExecute(NResponseModel responseModel) {
        super.onPostExecute(responseModel);
        if (listener != null) {
            if (responseModel != null) {
                if (!responseModel.isRedirectInterrupted())
                    listener.finishUI(responseModel);
                else if (listener instanceof NBaseCallback) {
                    ((NBaseCallback) listener).finishUIFailed();
                    ((NBaseCallback) listener).onRedirectInterrupted(responseModel.getRedirectLocation(), responseModel);
                }
            } else if (listener instanceof NBaseCallback) {
                ((NBaseCallback) listener).finishUIFailed();
                if (requestModel.isEnableDefaultListeners())
                    ((NBaseCallback) listener).preFailed(requestModel, NErrors.CONNECTION_ERROR);
                else
                    ((NBaseCallback) listener).onFailed(requestModel, NErrors.CONNECTION_ERROR);
            }
        }
    }


    //
    // Behavior
    //


    protected HttpURLConnection openConnection() throws Exception {
        URL url = new URL(requestModel.getUrl());
        return (HttpURLConnection) url.openConnection();
    }

    abstract protected HttpURLConnection setupConnection() throws Exception;

    protected void addHeaders(HttpURLConnection connection) throws UnsupportedEncodingException {
        StringBuilder logHeaders = new StringBuilder();
        for (NKeyValueModel header : requestModel.getHeaders()) {
            logHeaders.append(String.format("%s=%s; ", header.getKey(), header.getValue()));
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        NLog.logD("[Headers]: " + logHeaders.toString());
    }

    protected void addUrlParams() throws UnsupportedEncodingException {
        String urlParams;

        if ((requestModel.getMethod() instanceof QueryMethod)
                && requestModel.getQueryParams().isEmpty() && !requestModel.getParams().isEmpty()) {
            urlParams = NDataBuilder.getQuery(requestModel.getParams(), charset);
            if (!urlParams.isEmpty()) {
                requestModel.setUrl(requestModel.getUrl() + "?" + urlParams);
                NLog.logD("[Query params]: " + urlParams.replace("&", "; "));
            }
        } else if (!requestModel.getQueryParams().isEmpty()) {
            urlParams = NDataBuilder.getQuery(requestModel.getQueryParams(), charset);
            if (!urlParams.isEmpty()) {
                requestModel.setUrl(requestModel.getUrl() + "?" + urlParams);
                NLog.logD("[Query params]: " + urlParams.replace("&", "; "));
            }
        }
    }

    abstract protected void makeRequestBody(HttpURLConnection connection) throws IOException;

    protected Map<String, List<String>> getResponseHeaders(HttpURLConnection connection, String body) {
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (EasyNet.getInstance().isWriteLogs()) {
            if (headers != null) {
                StringBuilder headersLog = new StringBuilder();
                for (Map.Entry<String, List<String>> entry : headers.entrySet())
                    headersLog.append(entry.getKey() != null ? (entry.getKey() + "=") : "")
                            .append(entry.getValue().toString()).append("; ");
                NLog.logD("[Getting x headers]: ".replace("x", "" + headers.size()) + headersLog.toString());
            } else NLog.logD("[Headers empty]");

            if (!body.isEmpty())
                NLog.logD("[Body]: " + body);
            else NLog.logD("[Empty body]");
        }
        return headers;
    }

    protected InputStream getInputStreamFromConnection(HttpURLConnection connection) throws IOException {
        if (connection.getResponseCode() / 100 == 2)
            return connection.getInputStream();
        else return connection.getErrorStream();
    }

    protected String readResponseBody(InputStream inputStream) throws Exception {
        byte[] buf = new byte[BUFFER_SIZE];
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int bytesRead;
        int bytesBuffered = 0;
        while ((bytesRead = inputStream.read(buf)) > -1) {
            if (isCancelled()) {
                throw new Exception("Task was cancelled");
            }
            outputStream.write(buf, 0, bytesRead);
            bytesBuffered += bytesRead;
            if (bytesBuffered > 1024 * 1024) {
                bytesBuffered = 0;
                outputStream.flush();
            }
            // TODO: calculate progress speed
        }
        outputStream.flush();
        outputStream.close();
        return outputStream.toString();
    }


    //
    // Other
    //


    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    @Override
    protected void onCancelled() {
        if (listener != null && listener instanceof NBaseCallback)
            ((NBaseCallback) listener).preTaskCancelled(requestModel, tag);
    }


    //
    // Callback
    //


    public interface NTaskListener {
        void start(NRequestModel requestModel);

        void finishUI(NResponseModel responseModel);

        void finish(NResponseModel responseModel);

        /**
         * @param location new URL
         * @return true - if you want to continue redirect
         */
        boolean redirect(String location);
    }
}