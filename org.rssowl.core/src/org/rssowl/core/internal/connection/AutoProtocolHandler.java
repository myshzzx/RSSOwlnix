
package org.rssowl.core.internal.connection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.rssowl.core.Owl;
import org.rssowl.core.connection.ConnectionException;
import org.rssowl.core.connection.IConnectionPropertyConstants;
import org.rssowl.core.persist.IConditionalGet;
import org.rssowl.core.persist.IFeed;
import org.rssowl.core.persist.IModelFactory;
import org.rssowl.core.persist.INews;
import org.rssowl.core.util.Pair;
import org.rssowl.core.util.Triple;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * autohttp[s]://url.xxx~[css-query-selector]
 * <p>
 * e.g. Hacker News: <code>autohttps://news.ycombinator.com/newest~td.title a.storylink</code>
 *
 * @author mysh
 * @since 2019-06-11
 */
public class AutoProtocolHandler extends DefaultProtocolHandler {
  private int fNewsCounter;

  private static Log log = LogFactory.getLog(AutoProtocolHandler.class);

  @Override
  public Triple<IFeed, IConditionalGet, URI> reload(URI link, IProgressMonitor monitor, Map<Object, Object> properties) throws CoreException {
    IModelFactory typesFactory = Owl.getModelFactory();

    /* Create a new empty feed from the existing one */
    IFeed feed = typesFactory.createFeed(null, link);

    /* Add Monitor to support early cancelation */
    if (properties == null)
      properties = new HashMap<>();
    properties.put(IConnectionPropertyConstants.PROGRESS_MONITOR, monitor);

    InputStream inS = null;
    try {
      Pair<String, String> autoLink = parseAutoLink(link);
      URI pageLink = new URI(autoLink.getFirst());
      inS = openStream(pageLink, properties);

      /* Retrieve Conditional Get if present */
      IConditionalGet conditionalGet = getConditionalGet(link, inS);

      /* Return on Cancelation or Shutdown */
      if (monitor.isCanceled()) {
        closeStream(inS, true);
        return null;
      }

      /* Pass the Stream to the Interpreter */
      String selector = autoLink.getSecond();
      selector = java.net.URLDecoder.decode(selector, "UTF-8"); //$NON-NLS-1$
      loadFeed(inS, feed, pageLink, selector);
      return Triple.create(feed, conditionalGet, pageLink);
    } catch (Exception e) {
      log.error("parse auto schema fail:" + link.toString(), e); //$NON-NLS-1$
      /* Return on Cancelation or Shutdown */
      return null;
    } finally {
      if (inS != null && monitor.isCanceled()) {
        closeStream(inS, true);
      }
    }
  }

  private Pair<String, String> parseAutoLink(URI link) {
    String autoLink = link.toString();
    int selectorIdx = autoLink.lastIndexOf('~');
    String pageLink = autoLink.substring(4, selectorIdx);
    String selector = autoLink.substring(selectorIdx + 1);
    return Pair.create(pageLink, selector);
  }

  private void loadFeed(InputStream inS, IFeed feed, URI pageLink, String selector) throws IOException, URISyntaxException, InterruptedException {
    Document doc = Jsoup.parse(inS, null, pageLink.toString());
    Elements newsList = doc.select(selector);
    if (newsList == null || newsList.size() == 0)
      return;

    String root = pageLink.getScheme() + "://" + pageLink.getHost() + (pageLink.getPort() > 0 ? ":" + pageLink.getPort() : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    String path = pageLink.getPath();
    if (path.length() == 0)
      path = "/"; //$NON-NLS-1$
    String currentPath = path.substring(0, path.lastIndexOf('/') + 1);
    String rootCurrent = root + currentPath;

    ExecutorService exec = Executors.newFixedThreadPool(5);
    for (Element news : newsList) {
      String title = news.text();
      String url = news.attr("href"); //$NON-NLS-1$

      INews n = Owl.getModelFactory().createNews(null, feed, new Date(System.currentTimeMillis() - (fNewsCounter++ * 1)));
      n.setBase(feed.getBase());
      n.setTitle(title);
      n.setLink(new URI(url));
      if (n.getLink().getScheme() == null) {
        if (url.charAt(0) == '/') {
          n.setLink(new URI(root + url));
        } else {
          n.setLink(new URI(rootCurrent + url));
        }
      }
      exec.submit(() -> {
        InputStream in = null;
        try {
          in = openStream(n.getLink(), null);
          byte[] buf = readToByteBuffer(in);
          String html = new String(buf);
          html = html.replace("</head>", "<base href=\"" + rootCurrent + "\"></head>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
          n.setDescription(html);
        } catch (Exception e) {
          log.error("load content fail:" + feed.getLink() + "," + n.getLinkAsText(), e); //$NON-NLS-1$//$NON-NLS-2$
        } finally {
          if (in != null)
            closeStream(in, true);
        }
      });
    }
    exec.shutdown();
    exec.awaitTermination(5, TimeUnit.MINUTES);
  }

  private static byte[] readToByteBuffer(InputStream inStream) throws IOException {
    byte[] buffer = new byte[131072];
    ByteArrayOutputStream outStream = new ByteArrayOutputStream(131072);

    while (true) {
      int read = inStream.read(buffer);
      if (read < 0) {
        break;
      }
      outStream.write(buffer, 0, read);
    }

    return outStream.toByteArray();
  }

  @Override
  public String getLabel(URI link, IProgressMonitor monitor) {
    try {
      Pair<String, String> autoLink = this.parseAutoLink(link);
      return super.getLabel(new URI(autoLink.getFirst()), monitor);
    } catch (Exception e) {
      log.error("read autolink title error:" + link.toString(), e); //$NON-NLS-1$
      return null;
    }
  }

  @Override
  public URI getFeed(URI website, IProgressMonitor monitor) {
    return null;
  }

}
