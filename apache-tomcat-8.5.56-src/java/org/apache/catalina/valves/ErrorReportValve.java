/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.valves;

import java.io.IOException;
import java.io.Writer;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.TomcatCSS;
import org.apache.coyote.ActionCode;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.security.Escape;

/**
 * <p>Implementation of a Valve that outputs HTML error pages.</p>
 *
 * <p>This Valve should be attached at the Host level, although it will work
 * if attached to a Context.</p>
 *
 * <p>HTML code from the Cocoon 2 project.</p>
 *
 * @author Remy Maucherat
 * @author Craig R. McClanahan
 * @author <a href="mailto:nicolaken@supereva.it">Nicola Ken Barozzi</a> Aisa
 * @author <a href="mailto:stefano@apache.org">Stefano Mazzocchi</a>
 * @author Yoav Shapira
 */
public class ErrorReportValve extends ValveBase {

    private boolean showReport = true;

    private boolean showServerInfo = true;

    //------------------------------------------------------ Constructor
    public ErrorReportValve() {
        super(true);
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Invoke the next Valve in the sequence. When the invoke returns, check
     * the response state. If the status code is greater than or equal to 400
     * or an uncaught exception was thrown then the error handling will be
     * triggered.
     *
     * @param request The servlet request to be processed
     * @param response The servlet response to be created
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet error occurs
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // Perform the request
        // 1. 先将 请求转发给下一个 Valve
        getNext().invoke(request, response);

        // 2. 这里的 isCommitted 表明, 请求是正常处理结束
        if (response.isCommitted()) {
            if (response.setErrorReported()) {
                // Error wasn't previously reported but we can't write an error
                // page because the response has already been committed. Attempt
                // to flush any data that is still to be written to the client.
                try {
                    response.flushBuffer();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
                // Close immediately to signal to the client that something went
                // wrong
                response.getCoyoteResponse().action(ActionCode.CLOSE_NOW,
                        request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
            }
            return;
        }

        // 3. 判断请求过程中是否有异常发生
        Throwable throwable = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

        // If an async request is in progress and is not going to end once this
        // container thread finishes, do not process any error page here.
        if (request.isAsync() && !request.isAsyncCompleting()) {
            return;
        }

        if (throwable != null && !response.isError()) {
            // Make sure that the necessary methods have been called on the
            // response. (It is possible a component may just have set the
            // Throwable. Tomcat won't do that but other components might.)
            // These are safe to call at this point as we know that the response
            // has not been committed.
            // 4. 重置 response 里面的数据(此时 Response 里面可能有些数据)
            response.reset();
            // 5. 这就是我们常看到的 500 错误码
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        // One way or another, response.sendError() will have been called before
        // execution reaches this point and suspended the response. Need to
        // reverse that so this valve can write to the response.
        response.setSuspended(false);

        try {
            // 6. 这里就是将 异常的堆栈信息组合成 html 页面, 输出到前台
            report(request, response, throwable);
        } catch (Throwable tt) {
            ExceptionUtils.handleThrowable(tt);
        }
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Prints out an error report.
     *
     * @param request The request being processed
     * @param response The response being generated
     * @param throwable The exception that occurred (which possibly wraps
     *  a root cause exception
     */
    protected void report(Request request, Response response, Throwable throwable) {

        int statusCode = response.getStatus();

        // Do nothing on a 1xx, 2xx and 3xx status
        // Do nothing if anything has been written already
        // Do nothing if the response hasn't been explicitly marked as in error
        //    and that error has not been reported.
        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }

        // If an error has occurred that prevents further I/O, don't waste time
        // producing an error report that will never be read
        AtomicBoolean result = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, result);
        if (!result.get()) {
            return;
        }

        String message = Escape.htmlElementContent(response.getMessage());
        if (message == null) {
            if (throwable != null) {
                String exceptionMessage = throwable.getMessage();
                if (exceptionMessage != null && exceptionMessage.length() > 0) {
                    try (Scanner scanner = new Scanner(exceptionMessage)) {
                        message = Escape.htmlElementContent(scanner.nextLine());
                    }
                }
            }
            if (message == null) {
                message = "";
            }
        }

        // Do nothing if there is no reason phrase for the specified status code and
        // no error message provided
        String reason = null;
        String description = null;
        StringManager smClient = StringManager.getManager(
                Constants.Package, request.getLocales());
        response.setLocale(smClient.getLocale());
        try {
            reason = smClient.getString("http." + statusCode + ".reason");
            description = smClient.getString("http." + statusCode + ".desc");
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
        }
        if (reason == null || description == null) {
            if (message.isEmpty()) {
                return;
            } else {
                reason = smClient.getString("errorReportValve.unknownReason");
                description = smClient.getString("errorReportValve.noDescription");
            }
        }

        StringBuilder sb = new StringBuilder();

        sb.append("<!doctype html><html lang=\"");
        sb.append(smClient.getLocale().getLanguage()).append("\">");
        sb.append("<head>");
        sb.append("<title>");
        sb.append(smClient.getString("errorReportValve.statusHeader",
                String.valueOf(statusCode), reason));
        sb.append("</title>");
        sb.append("<style type=\"text/css\">");
        sb.append(TomcatCSS.TOMCAT_CSS);
        sb.append("</style>");
        sb.append("</head><body>");
        sb.append("<h1>");
        sb.append(smClient.getString("errorReportValve.statusHeader",
                String.valueOf(statusCode), reason)).append("</h1>");
        if (isShowReport()) {
            sb.append("<hr class=\"line\" />");
            sb.append("<p><b>");
            sb.append(smClient.getString("errorReportValve.type"));
            sb.append("</b> ");
            if (throwable != null) {
                sb.append(smClient.getString("errorReportValve.exceptionReport"));
            } else {
                sb.append(smClient.getString("errorReportValve.statusReport"));
            }
            sb.append("</p>");
            if (!message.isEmpty()) {
                sb.append("<p><b>");
                sb.append(smClient.getString("errorReportValve.message"));
                sb.append("</b> ");
                sb.append(message).append("</p>");
            }
            sb.append("<p><b>");
            sb.append(smClient.getString("errorReportValve.description"));
            sb.append("</b> ");
            sb.append(description);
            sb.append("</p>");
            if (throwable != null) {
                String stackTrace = getPartialServletStackTrace(throwable);
                sb.append("<p><b>");
                sb.append(smClient.getString("errorReportValve.exception"));
                sb.append("</b></p><pre>");
                sb.append(Escape.htmlElementContent(stackTrace));
                sb.append("</pre>");

                int loops = 0;
                Throwable rootCause = throwable.getCause();
                while (rootCause != null && (loops < 10)) {
                    stackTrace = getPartialServletStackTrace(rootCause);
                    sb.append("<p><b>");
                    sb.append(smClient.getString("errorReportValve.rootCause"));
                    sb.append("</b></p><pre>");
                    sb.append(Escape.htmlElementContent(stackTrace));
                    sb.append("</pre>");
                    // In case root cause is somehow heavily nested
                    rootCause = rootCause.getCause();
                    loops++;
                }

                sb.append("<p><b>");
                sb.append(smClient.getString("errorReportValve.note"));
                sb.append("</b> ");
                sb.append(smClient.getString("errorReportValve.rootCauseInLogs"));
                sb.append("</p>");

            }
            sb.append("<hr class=\"line\" />");
        }
        if (isShowServerInfo()) {
            sb.append("<h3>").append(ServerInfo.getServerInfo()).append("</h3>");
        }
        sb.append("</body></html>");

        try {
            try {
                response.setContentType("text/html");
                response.setCharacterEncoding("utf-8");
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (container.getLogger().isDebugEnabled()) {
                    container.getLogger().debug("status.setContentType", t);
                }
            }
            Writer writer = response.getReporter();
            if (writer != null) {
                // If writer is null, it's an indication that the response has
                // been hard committed already, which should never happen
                writer.write(sb.toString());
                response.finishResponse();
            }
        } catch (IOException e) {
            // Ignore
        } catch (IllegalStateException e) {
            // Ignore
        }

    }


    /**
     * Print out a partial servlet stack trace (truncating at the last
     * occurrence of javax.servlet.).
     * @param t The stack trace to process
     * @return the stack trace relative to the application layer
     */
    protected String getPartialServletStackTrace(Throwable t) {
        StringBuilder trace = new StringBuilder();
        trace.append(t.toString()).append(System.lineSeparator());
        StackTraceElement[] elements = t.getStackTrace();
        int pos = elements.length;
        for (int i = elements.length - 1; i >= 0; i--) {
            if ((elements[i].getClassName().startsWith
                 ("org.apache.catalina.core.ApplicationFilterChain"))
                && (elements[i].getMethodName().equals("internalDoFilter"))) {
                pos = i;
                break;
            }
        }
        for (int i = 0; i < pos; i++) {
            if (!(elements[i].getClassName().startsWith
                  ("org.apache.catalina.core."))) {
                trace.append('\t').append(elements[i].toString()).append(System.lineSeparator());
            }
        }
        return trace.toString();
    }

    /**
     * Enables/Disables full error reports
     *
     * @param showReport <code>true</code> to show full error data
     */
    public void setShowReport(boolean showReport) {
        this.showReport = showReport;
    }

    public boolean isShowReport() {
        return showReport;
    }

    /**
     * Enables/Disables server info on error pages
     *
     * @param showServerInfo <code>true</code> to show server info
     */
    public void setShowServerInfo(boolean showServerInfo) {
        this.showServerInfo = showServerInfo;
    }

    public boolean isShowServerInfo() {
        return showServerInfo;
    }
}
