
package org.rssowl.core.tests;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.MovedContextHandler;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ByteArrayISO8859Writer;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/*   **********************************************************************  **
 **   Copyright notice                                                       **
 **                                                                          **
 **   (c) 2005-2011 RSSOwl Development Team                                  **
 **   http://www.rssowl.org/                                                 **
 **                                                                          **
 **   All rights reserved                                                    **
 **                                                                          **
 **   This program and the accompanying materials are made available under   **
 **   the terms of the Eclipse Public License v1.0 which accompanies this    **
 **   distribution, and is available at:                                     **
 **   http://www.rssowl.org/legal/epl-v10.html                               **
 **                                                                          **
 **   A copy is found in the file epl-v10.html and important notices to the  **
 **   license from the team is found in the textfile LICENSE.txt distributed **
 **   in this package.                                                       **
 **                                                                          **
 **   This copyright notice MUST APPEAR in all copies of the file!           **
 **                                                                          **
 **   Contributors:                                                          **
 **     RSSOwl Development Team - initial API and implementation             **
 **                                                                          **
 **  **********************************************************************  */

/**
 * https://www.eclipse.org/jetty/documentation/9.4.x/embedded-examples.html
 */
public class TestWebServer {
  private static final String updateSiteLocalFolder = "file:///S:\\CODE\\ProjectsMy\\java\\rssowl\\export\\update_site_2_3_1\\rssowl";
  private static final String updateSiteRemoteFolder = "https://xyrio.github.io/RSSOwlnix-site/p2/program";
  private static final String webServerRootFolder = "data";
  private static Server webServer;
  private static String host = "127.0.0.1";
  private static int port = 88;
  private static int portSsl = 8843;
  private static StatisticsHandler statisticsHandler;

  public static final String rootHttp = "http://" + host + ":" + port;
  public static final String rootHttps = "https://" + host + ":" + portSsl;
  public static final String username = "super";
  public static final String password = "1234";

  public static void main(String[] args) {
    required(false);
  }

  private static void startShutdownTimer(boolean autoShutdown) {
    if (autoShutdown) {
      final long msCheckInterval = 1000;
      final long msShutdownDuration = 5000;

      Timer timer = new Timer("webServerShutdownTimer", true);
      timer.scheduleAtFixedRate(new TimerTask() {
        long lastBytesCount = 0;
        long durationIdlingMS = 0;

        @Override
        public void run() {
          synchronized (TestWebServer.class) {
            long currentBytesCount = statisticsHandler.getResponsesBytesTotal();
            System.out.println("*** TestWebServer: currentBytesCount=" + currentBytesCount);
            if (currentBytesCount != lastBytesCount) {
              lastBytesCount = currentBytesCount; //changed
              durationIdlingMS = 0;
            } else {
              durationIdlingMS += msCheckInterval; //idling
            }
            if (durationIdlingMS >= msShutdownDuration) {
              try {
                System.out.println("*** TestWebServer: shutting down due to inactivity for over ms=" + msShutdownDuration);
                webServer.stop();
                timer.cancel();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          }
        }
      }, 0, msCheckInterval);
    }
  }

  /**
   * <pre>
   * - port: http
   * - portSsl: https
   *
   * - /feed/: shares the tests/data/ folder
   * - /feed-no-listing/: public but no listing of content
   * - /redirect-feed/: redirects to /feed/
   * - /auth-feed/: requires authentication
   * - /auth-feed-no-listing/: requires authentication and no listing of content
   * - /rssowlnix/: shares local update site folder, set updateSiteLocalFolder above
   * </pre>
   */
  public static void required(boolean autoShutdown) {
    synchronized (TestWebServer.class) {

      if (webServer != null)
        return;

      System.out.println("*** TestWebServer: starting webserver...");

      NCSARequestLog requestLog = new NCSARequestLog();
//    requestLog.setFilename("/path/to/my/logs/yyyy_mm_dd.request.log");
//    requestLog.setFilenameDateFormat("yyyy_MM_dd");
//    requestLog.setAppend(true);
//    requestLog.setRetainDays(1);
      requestLog.setExtended(true);
      requestLog.setLogCookies(false);
      requestLog.setLogTimeZone("GMT");
      RequestLogHandler requestLogHandler = new RequestLogHandler();
      requestLogHandler.setRequestLog(requestLog);

      try {
        String jettyDistKeystore = "unimportant_rsa_2048.keystore";
        File keystoreFile = new File(jettyDistKeystore);
        if (!keystoreFile.exists())
          throw new FileNotFoundException(keystoreFile.getAbsolutePath());

        final Server server = new Server();
        server.setStopAtShutdown(true);
        server.setStopTimeout(1000);
        {
          HttpConfiguration httpConfig = new HttpConfiguration();
          httpConfig.setSecureScheme("https");
          httpConfig.setSecurePort(portSsl);
          httpConfig.setOutputBufferSize(32768);

          ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
          http.setHost(host);
          http.setPort(port);
          http.setIdleTimeout(5000);

          SslContextFactory sslContextFactory = new SslContextFactory();
          sslContextFactory.setKeyStorePath(keystoreFile.getAbsolutePath());
          sslContextFactory.setKeyStorePassword("somepassstore"); //when changed must recreate keystore
          sslContextFactory.setKeyManagerPassword("somepasskey"); //when changed must recreate keystore

          // OPTIONAL: Un-comment the following to use Conscrypt for SSL instead of
          // the native JSSE implementation.

//      Security.addProvider(new OpenSSLProvider());
//      sslContextFactory.setProvider("Conscrypt");

          HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
          SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
          secureRequestCustomizer.setStsMaxAge(2000);
          secureRequestCustomizer.setStsIncludeSubDomains(true);
          httpsConfig.addCustomizer(secureRequestCustomizer);

          ServerConnector https = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(httpsConfig));
          https.setHost(host);
          https.setPort(portSsl);
          https.setIdleTimeout(5000);

          server.setConnectors(new Connector[] { http, https });
        }

//        Collection<ServletHolder> servletHolders = new ArrayList<>();

        ContextHandler feedHandler = handlerRessources("/feed/", webServerRootFolder);
//        servletHolders.add(serveletRessources("/feed/*", webServerRootFolder));

        ContextHandler feedNoListingHandler = handlerRessources("/feed-no-listing/", webServerRootFolder, false);
//        servletHolders.add(serveletRessources("/feed/*", webServerRootFolder));

        MovedContextHandler redirectHandler = handlerRedirect("/redirect-feed/", "/feed/");
//        servletHolders.add(serveletRedirect("/redirect-feed/", "/feed/"));

        ContextHandler authBasicHandler = handlerRessources("/auth-feed/", webServerRootFolder);
        ConstraintSecurityHandler securityBasicHandler = handlerSecurity("/auth-feed/*", new BasicAuthenticator(), "admin");

//        servletHolders.add(serveletRessources("/auth-feed/*", webServerRootFolder));

        ContextHandler quitHandler = new ContextHandler("/manual-quit/");
        quitHandler.setHandler(new DefaultHandler() {
          @Override
          public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            new Thread(new Runnable() {
              @Override
              public void run() {
                try {
                  server.stop();
                } catch (Exception e) {
                  e.printStackTrace();
                }
              }
            }).start();
            super.handle(target, baseRequest, request, response);
          }
        });

        ContextHandler updateProgramHandler = handlerRessources("/rssowlnix/", updateSiteLocalFolder);

        DefaultHandler defaultHandler = new DefaultHandler() {
          @SuppressWarnings("unused")
          @Override
          public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            //favicon.ico request goes through here
            //super.handle(target, baseRequest, request, response); //prevent 404 return

            //copy from DefaultHandler but changed to return 200 instead of 404

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MimeTypes.Type.TEXT_HTML.toString());

            try (ByteArrayISO8859Writer writer = new ByteArrayISO8859Writer(1500);) {
//                writer.write("<HTML>\n<HEAD>\n<TITLE>Error 404 - Not Found");
//                writer.write("</TITLE>\n<BODY>\n<H2>Error 404 - Not Found.</H2>\n");
//                writer.write("No context on this server matched or handled this request.<BR>");
              writer.write("Contexts known to this server are: <ul>");

              Server server = getServer();
              Handler[] handlers = server == null ? null : server.getChildHandlersByClass(ContextHandler.class);

              for (int i = 0; handlers != null && i < handlers.length; i++) {
                ContextHandler context = (ContextHandler) handlers[i];
                if (context.isRunning()) {
                  writer.write("<li><a href=\"");
                  if (context.getVirtualHosts() != null && context.getVirtualHosts().length > 0)
                    writer.write(request.getScheme() + "://" + context.getVirtualHosts()[0] + ":" + request.getLocalPort());
                  writer.write(context.getContextPath());
                  if (context.getContextPath().length() > 1 && context.getContextPath().endsWith("/"))
                    writer.write("/");
                  writer.write("\">");
                  writer.write(context.getContextPath());
                  if (context.getVirtualHosts() != null && context.getVirtualHosts().length > 0)
                    writer.write("&nbsp;@&nbsp;" + context.getVirtualHosts()[0] + ":" + request.getLocalPort());
                  writer.write("&nbsp;--->&nbsp;");
                  writer.write(context.toString());
                  writer.write("</a></li>\n");
                } else {
                  writer.write("<li>");
                  writer.write(context.getContextPath());
                  if (context.getVirtualHosts() != null && context.getVirtualHosts().length > 0)
                    writer.write("&nbsp;@&nbsp;" + context.getVirtualHosts()[0] + ":" + request.getLocalPort());
                  writer.write("&nbsp;--->&nbsp;");
                  writer.write(context.toString());
                  if (context.isFailed())
                    writer.write(" [failed]");
                  if (context.isStopped())
                    writer.write(" [stopped]");
                  writer.write("</li>\n");
                }
              }

              writer.write("</ul><hr>");

//                baseRequest.getHttpChannel().getHttpConfiguration()
//                    .writePoweredBy(writer,"<a href=\"http://eclipse.org/jetty\"><img border=0 src=\"/favicon.ico\"/></a>&nbsp;","<hr/>\n");

//                writer.write("\n</BODY>\n</HTML>\n");
              writer.flush();
              response.setContentLength(writer.size());
              try (OutputStream out = response.getOutputStream()) {
                writer.writeTo(out);
              }
            }
          }
        };

        securityBasicHandler.setHandler(authBasicHandler);

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { requestLogHandler, updateProgramHandler, feedHandler, feedNoListingHandler//
            , redirectHandler, securityBasicHandler, quitHandler, defaultHandler });

//        // ### servlets start
//        ServletContextHandler servletHandlers = new ServletContextHandler();
//        {
//          servletHandlers.setContextPath("/");
//          for (ServletHolder servletHolder : servletHolders)
//            servletHandlers.addServlet(servletHolder, servletHolder.getName());
//
//          //root last, required by spec
//          ServletHolder holderRoot = new ServletHolder("default", new HttpServlet() {
//            @SuppressWarnings("unused")
//            @Override
//            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
//
//              StringBuilder sb = new StringBuilder();
//              sb.append("<html><head></head><body>\n");
//              for (ServletHolder sh : servletHolders) {
//                sb.append("  <p><a href=\"").append(sh.getName()).append("\">");
//                sb.append(sh.getName());
//                sb.append(" -> ");
//                sb.append(sh.getInitParameter("resourceBase"));
//                sb.append("</a></p>\n");
//              }
//              sb.append("</body></html>");
//              String content = sb.toString();
//              resp.setContentLength(sb.length());
//              resp.getWriter().write(content);
//              resp.setContentType("text/html");
//              resp.setStatus(HttpServletResponse.SC_OK);
//            }
//
//          });
////        ret.setResourceBase(resolveIfAlias(webServerRootFolder));
////        holderRoot.setInitParameter("resourceBase", resolveIfAlias(webServerRootFolder));
////        holderRoot.setInitParameter("dirAllowed", "true");
//          servletHandlers.addServlet(holderRoot, "/");
//
////          securityHandler.setHandler(servletHandlers);
//          // ### servlets end
//        }

        statisticsHandler = new StatisticsHandler();
        statisticsHandler.setHandler(handlers);

        server.setHandler(statisticsHandler);

//    System.out.println(server.dump());
        server.start();
        webServer = server;
        startShutdownTimer(autoShutdown);
        System.out.println("*** TestWebServer: started webserver");
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  /** server ignores aliases instead of using real location */
  private static String resolveIfAlias(String sdir) {

    try {
      Resource dir = Resource.newResource(sdir);
      if (dir.isAlias())
        sdir = dir.getAlias().toString();
      return sdir;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static MovedContextHandler handlerRedirect(String fromUrl, String toUrl) {

    MovedContextHandler ret = new MovedContextHandler();
    ret.setContextPath(fromUrl);
    ret.setNewContextURL(toUrl);
    ret.setPermanent(true);
    ret.setDiscardPathInfo(false);
    ret.setDiscardQuery(false);
    return ret;
  }

  public static ServletHolder serveletRedirect(String fromUrl, String toUrl) {
    return new ServletHolder(fromUrl, new HttpServlet() {
      @SuppressWarnings("unused")
      @Override
      protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.sendRedirect(toUrl);
      }
    });
  }

  private static ContextHandler handlerRessources(String contextUrl, String sdir) {
    return handlerRessources(contextUrl, sdir, true);
  }

  private static ContextHandler handlerRessources(String contextUrl, String sdir, boolean isDirAllowed) {

    ContextHandler ret = new ContextHandler(contextUrl);
    ret.setHandler(handlerRessources(sdir, isDirAllowed));
    return ret;
  }

  private static ResourceHandler handlerRessources(String sdir, boolean isDirAllowed) {

    String sdirResolved = resolveIfAlias(sdir);
    ResourceHandler ret = new ResourceHandler();
    ret.setDirAllowed(isDirAllowed);
    ret.setResourceBase(sdirResolved);
    ret.setCacheControl("public, max-age=600"); //cache 10 min
    ret.setEtags(true);
    ret.setAcceptRanges(true);
    return ret;
  }

  private static ServletHolder serveletRessources(String contextUrl, String sdir) {

    ServletHolder holder = new ServletHolder(contextUrl, DefaultServlet.class);
    holder.setInitParameter("resourceBase", resolveIfAlias(sdir));
    holder.setInitParameter("dirAllowed", "true");
    holder.setInitParameter("pathInfoOnly", "true");
//    holder.setInitParameter("cacheControl", "public, max-age=600");
//    holder.setInitParameter("etags", "true");
    return holder;
  }

  private static ConstraintSecurityHandler handlerSecurity(String contextUrl, Authenticator authenticator, String... rolesAccept) {
    ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();

    UserStore userStore = new UserStore();
//    userStore.addUser("usr", new Password("pw"), new String[] { "user" });
    userStore.addUser(username, new Password(password), new String[] { "user", "admin" });

    HashLoginService loginService = new HashLoginService();
    loginService.setName(authenticator.getAuthMethod() + " Restricted Directory"); //used as realm name
//    loginService.setConfig(realmPropertiesFile.getAbsolutePath());
    loginService.setUserStore(userStore);
    loginService.setHotReload(false);
//    server.addBean(loginService);

    Constraint constraint = new Constraint();
    constraint.setAuthenticate(true);
//    constraint.setName("usr");
//    constraint.setName("Secure resources");
//  constraint.setRoles(new String[] { "user", "admin" });
    constraint.setRoles(rolesAccept);

    ConstraintMapping mapping = new ConstraintMapping();
    mapping.setPathSpec(contextUrl);
    mapping.setConstraint(constraint);

//    securityHandler.setRealmName("TestSecurityHandlerRealmName"); //is ignored. login service name is used instead
    securityHandler.setConstraintMappings(Collections.singletonList(mapping));
    //auth method
    // http://www.eclipse.org/jetty/documentation/current/configuring-security.html#configuring-security-authentication
    securityHandler.setAuthenticator(authenticator);
//    securityHandler.setAuthenticator(new BasicAuthenticator());
    //TODO other authentication methods
//    securityHandler.setAuthenticator(new DigestAuthenticator());
//    securityHandler.setAuthenticator(new FormAuthenticator());
//    securityHandler.setAuthenticator(new ClientCertAuthenticator());
//    securityHandler.setAuthenticator(new SpnegoAuthenticator());
    securityHandler.setLoginService(loginService);

    return securityHandler;
  }

}
