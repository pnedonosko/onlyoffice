/*
 * Copyright (C) 2003-2019 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.onlyoffice.cometd;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.jcr.RepositoryException;

import org.cometd.annotation.Param;
import org.cometd.annotation.ServerAnnotationProcessor;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.LifeCycle;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;
import org.picocontainer.Startable;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.onlyoffice.Config.Editor;
import org.exoplatform.onlyoffice.DocumentStatus;
import org.exoplatform.onlyoffice.OnlyofficeEditorException;
import org.exoplatform.onlyoffice.OnlyofficeEditorListener;
import org.exoplatform.onlyoffice.OnlyofficeEditorService;
import org.exoplatform.onlyoffice.Userdata;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The CometdOnlyofficeService.
 */
public class CometdOnlyofficeService implements Startable {

  /**
   * Command thread factory adapted from {@link Executors#DefaultThreadFactory}.
   */
  static class CommandThreadFactory implements ThreadFactory {

    /** The group. */
    final ThreadGroup   group;

    /** The thread number. */
    final AtomicInteger threadNumber = new AtomicInteger(1);

    /** The name prefix. */
    final String        namePrefix;

    /**
     * Instantiates a new command thread factory.
     *
     * @param namePrefix the name prefix
     */
    CommandThreadFactory(String namePrefix) {
      SecurityManager s = System.getSecurityManager();
      this.group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
      this.namePrefix = namePrefix;
    }

    public Thread newThread(Runnable r) {
      Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0) {

        /**
         * {@inheritDoc}
         */
        @Override
        protected void finalize() throws Throwable {
          super.finalize();
          threadNumber.decrementAndGet();
        }

      };
      if (t.isDaemon()) {
        t.setDaemon(false);
      }
      if (t.getPriority() != Thread.NORM_PRIORITY) {
        t.setPriority(Thread.NORM_PRIORITY);
      }
      return t;
    }
  }

  /**
   * The Class ContainerCommand.
   */
  abstract class ContainerCommand implements Runnable {

    /** The container name. */
    final String containerName;

    /**
     * Instantiates a new container command.
     *
     * @param containerName the container name
     */
    ContainerCommand(String containerName) {
      this.containerName = containerName;
    }

    /**
     * Execute actual work of the commend (in extending class).
     *
     * @param exoContainer the exo container
     */
    abstract void execute(ExoContainer exoContainer);

    /**
     * Callback to execute on container error.
     *
     * @param error the error
     */
    abstract void onContainerError(String error);

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      // Do the work under eXo container context (for proper work of eXo apps
      // and JPA storage)
      ExoContainer exoContainer = ExoContainerContext.getContainerByName(containerName);
      if (exoContainer != null) {
        ExoContainer contextContainer = ExoContainerContext.getCurrentContainerIfPresent();
        try {
          // Container context
          ExoContainerContext.setCurrentContainer(exoContainer);
          RequestLifeCycle.begin(exoContainer);
          // do the work here
          execute(exoContainer);
        } finally {
          // Restore context
          RequestLifeCycle.end();
          ExoContainerContext.setCurrentContainer(contextContainer);
        }
      } else {
        // LOG.warn("Container not found " + containerName + " for remote call "
        // + contextName);
        onContainerError("Container not found");
      }

    }
  }

  /** The Constant LOG. */
  private static final Log                LOG                    = ExoLogger.getLogger(CometdOnlyofficeService.class);

  /** The channel name. */
  public static final String              CHANNEL_NAME           = "/eXo/Application/Onlyoffice/editor/";

  /** The channel name. */
  public static final String              CHANNEL_NAME_PARAMS    = CHANNEL_NAME + "{docId}";

  /** The document saved event. */
  public static final String              DOCUMENT_SAVED_EVENT   = "DOCUMENT_SAVED";

  /** The document changed event. */
  public static final String              DOCUMENT_CHANGED_EVENT = "DOCUMENT_CHANGED";

  /** The document version event. */
  public static final String              DOCUMENT_VERSION_EVENT = "DOCUMENT_VERSION";

  /** The document link event. */
  public static final String              DOCUMENT_LINK_EVENT    = "DOCUMENT_LINK";

  /** The editor closed event. */
  public static final String              EDITOR_CLOSED_EVENT    = "EDITOR_CLOSED";

  /**
   * Base minimum number of threads for document updates thread executors.
   */
  public static final int                 MIN_THREADS            = 2;

  /**
   * Minimal number of threads maximum possible for document updates thread
   * executors.
   */
  public static final int                 MIN_MAX_THREADS        = 4;

  /** Thread idle time for thread executors (in seconds). */
  public static final int                 THREAD_IDLE_TIME       = 120;

  /**
   * Maximum threads per CPU for thread executors of document changes channel.
   */
  public static final int                 MAX_FACTOR             = 20;

  /**
   * Queue size per CPU for thread executors of document updates channel.
   */
  public static final int                 QUEUE_FACTOR           = MAX_FACTOR * 2;

  /**
   * Thread name used for the executor.
   */
  public static final String              THREAD_PREFIX          = "onlyoffice-comet-thread-";

  /** The Onlyoffice editor service. */
  protected final OnlyofficeEditorService editors;

  /** The exo bayeux. */
  protected final EXoContinuationBayeux   exoBayeux;

  /** The service. */
  protected final CometdService           service;

  /** The call handlers. */
  protected final ExecutorService         eventsHandlers;

  /**
   * Instantiates the CometdOnlyofficeService.
   *
   * @param exoBayeux the exoBayeux
   * @param onlyofficeEditorService the onlyoffice editor service
   */
  public CometdOnlyofficeService(EXoContinuationBayeux exoBayeux, OnlyofficeEditorService onlyofficeEditorService) {
    this.exoBayeux = exoBayeux;
    this.editors = onlyofficeEditorService;
    this.service = new CometdService();
    this.eventsHandlers = createThreadExecutor(THREAD_PREFIX, MAX_FACTOR, QUEUE_FACTOR);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    // instantiate processor after the eXo container start, to let
    // start-dependent logic worked before us
    final AtomicReference<ServerAnnotationProcessor> processor = new AtomicReference<>();
    // need initiate process after Bayeux server starts
    exoBayeux.addLifeCycleListener(new LifeCycle.Listener() {
      @Override
      public void lifeCycleStarted(LifeCycle event) {
        ServerAnnotationProcessor p = new ServerAnnotationProcessor(exoBayeux);
        processor.set(p);
        p.process(service);
      }

      @Override
      public void lifeCycleStopped(LifeCycle event) {
        ServerAnnotationProcessor p = processor.get();
        if (p != null) {
          p.deprocess(service);
        }
      }

      @Override
      public void lifeCycleStarting(LifeCycle event) {
        // Nothing
      }

      @Override
      public void lifeCycleFailure(LifeCycle event, Throwable cause) {
        // Nothing
      }

      @Override
      public void lifeCycleStopping(LifeCycle event) {
        // Nothing
      }
    });

    if (PropertyManager.isDevelopping()) {
      // This listener not required for work, just for info during development
      exoBayeux.addListener(new BayeuxServer.SessionListener() {
        @Override
        public void sessionRemoved(ServerSession session, boolean timedout) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("sessionRemoved: " + session.getId() + " timedout:" + timedout + " channels: "
                + channelsAsString(session.getSubscriptions()));
          }
        }

        @Override
        public void sessionAdded(ServerSession session, ServerMessage message) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("sessionAdded: " + session.getId() + " channels: " + channelsAsString(session.getSubscriptions()));
          }
        }
      });
    }
  }

  /**
   * The CometService is responsible for sending messages to Cometd channels
   * when a document is saved.
   */
  @Service("onlyoffice")
  public class CometdService {

    /** The bayeux. */
    @Inject
    private BayeuxServer    bayeux;

    /** The local session. */
    @Session
    private LocalSession    localSession;

    /** The server session. */
    @Session
    private ServerSession   serverSession;

    /**  The listener service. */
    private ListenerService listenerService;

    /**
     * Post construct.
     */
    @PostConstruct
    public void postConstruct() {
      listenerService = PortalContainer.getInstance().getComponentInstanceOfType(ListenerService.class);
      editors.addListener(new OnlyofficeEditorListener() {

        @Override
        public void onSaved(DocumentStatus status) {
          publishSavedEvent(status.getConfig().getDocId(), status.getLastUser());
        }

        @Override
        public void onLeaved(DocumentStatus status) {
          // Nothing
        }

        @Override
        public void onJoined(DocumentStatus status) {
          // Nothing
        }

        @Override
        public void onGet(DocumentStatus status) {
          // Nothing
        }

        @Override
        public void onError(DocumentStatus status) {
          // Nothing
        }

        @Override
        public void onCreate(DocumentStatus status) {
          // Nothing
        }
      });
    }

    /**
     * Subscribe document events.
     *
     * @param message the message.
     * @param docId the docId.
     * @throws OnlyofficeEditorException the onlyoffice editor exception
     * @throws RepositoryException the repository exception
     */
    @Subscription(CHANNEL_NAME_PARAMS)
    public void subscribeDocuments(Message message, @Param("docId") String docId) throws OnlyofficeEditorException,
                                                                                  RepositoryException {
      Object objData = message.getData();
      if (!Map.class.isInstance(objData)) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Couldn't get data as a map from event");
        }
        return;
      }

      Map<String, Object> data = message.getDataAsMap();
      String type = (String) data.get("type");

      switch (type) {
      case DOCUMENT_CHANGED_EVENT:
        handleDocumentChangeEvent(data, docId);
        break;
      case DOCUMENT_VERSION_EVENT:
        handleDocumentVersionEvent(data, docId);
        break;
      case DOCUMENT_LINK_EVENT:
        handleDocumentLinkEvent(data, docId);
        break;
      case EDITOR_CLOSED_EVENT:
        handleEditorClosedEvent(data, docId);
        break;
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Event published in " + message.getChannel() + ", docId: " + docId + ", data: " + message.getJSON());
      }
    }

    /**
     * Handle document link event.
     *
     * @param data the data
     * @param docId the doc id
     */
    protected void handleDocumentLinkEvent(Map<String, Object> data, String docId) {
      String userId = (String) data.get("userId");
      String key = (String) data.get("key");
      // Saving a link
      editors.forceSave(new Userdata(userId, key, false, false));
    }

    /**
     * Handle editor closed event.
     *
     * @param data the data
     * @param docId the doc id
     */
    protected void handleEditorClosedEvent(Map<String, Object> data, String docId) {
      String userId = (String) data.get("userId");
      String key = (String) data.get("key");
      Boolean changes = (Boolean) data.get("changes");
      if (changes != null && changes.booleanValue()) {
        try {
          String[] users = editors.getState(userId, key).getUsers();
          // Don't call forceSave if it's the last user.
          if (users.length > 0) {
            editors.forceSave(new Userdata(userId, key, true, false));
          }
        } catch (OnlyofficeEditorException e) {
          LOG.error("Cannot get state of document key: " + key + ", user: " + userId);
        }
      }
    }

    /**
     * Handle document version event.
     *
     * @param data the data
     * @param docId the doc id
     */
    protected void handleDocumentVersionEvent(Map<String, Object> data, String docId) {
      String userId = (String) data.get("userId");
      String key = (String) data.get("key");
      Editor.User lastUser = editors.getLastModifier(key);
      if (LOG.isDebugEnabled()) {
        if (lastUser != null) {
          LOG.debug("Handle document version: {} for {}, lastUser: {}. LastSaved: {}",
                    userId,
                    docId,
                    lastUser.getId(),
                    lastUser.getLastSaved());
        } else {
          LOG.debug("Handle document version: {} for {}, lastUser: null", userId, docId);
        }
      }

      eventsHandlers.submit(new ContainerCommand(PortalContainer.getCurrentPortalContainerName()) {
        @Override
        void onContainerError(String error) {
          LOG.error("An error has occured in container: {}", containerName);
        }

        @Override
        void execute(ExoContainer exoContainer) {
          Editor.User user = editors.getUser(key, userId);
          if (user.getLinkSaved() >= user.getLastModified()) {
            LOG.debug("Downloading from existing link. User: {}, Key: {}, Link: {}", user.getId(), key, user.getDownloadLink());
            editors.downloadVersion(new Userdata(userId, key, false, false), user.getDownloadLink());
          } else {
            editors.forceSave(new Userdata(userId, key, true, false));
          }
        }
      });
    }

    /**
     * Handle document change event.
     *
     * @param data the data
     * @param docId the doc id
     */
    protected void handleDocumentChangeEvent(Map<String, Object> data, String docId) {
      String userId = (String) data.get("userId");
      String key = (String) data.get("key");
      Editor.User lastUser = editors.getLastModifier(key);

      // We download user version if another user started changing the document
      if (lastUser != null && !userId.equals(lastUser.getId()) && lastUser.getLastModified() > lastUser.getLastSaved()) {
        eventsHandlers.submit(new ContainerCommand(PortalContainer.getCurrentPortalContainerName()) {
          @Override
          void onContainerError(String error) {
            LOG.error("An error has occured in container: {}", containerName);
          }

          @Override
          void execute(ExoContainer exoContainer) {
            // If we have an actual link, download from it. Otherwise - ask the
            // command server for the link.
            if (lastUser.getLinkSaved() >= lastUser.getLastModified()) {
              LOG.debug("Downloading from existing link. User: {}, Key: {}, Link: {}",
                        lastUser.getId(),
                        key,
                        lastUser.getDownloadLink());
              editors.downloadVersion(new Userdata(lastUser.getId(), key, false, true), lastUser.getDownloadLink());
            } else {
              if (LOG.isDebugEnabled()) {
                LOG.debug("Download a new version of document: user " + lastUser.getId() + ", docId: " + docId);
              }
              editors.forceSave(new Userdata(lastUser.getId(), key, true, true));
            }

          }
        });
        if (LOG.isDebugEnabled()) {
          LOG.debug("Started collecting changes for: " + userId + ", docId: " + docId);
        }
      }
      editors.setLastModifier(key, userId);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Changes collected from: " + userId + ", docId: " + docId);
      }
    }

    /**
     * Publish saved event.
     *
     * @param docId the doc id
     * @param userId the user id
     */
    protected void publishSavedEvent(String docId, String userId) {
      ServerChannel channel = bayeux.getChannel(CHANNEL_NAME + docId);
      if (channel != null) {
        StringBuilder data = new StringBuilder();
        data.append('{');
        data.append("\"type\": \"");
        data.append(DOCUMENT_SAVED_EVENT);
        data.append("\", ");
        data.append("\"docId\": \"");
        data.append(docId);
        data.append("\", ");
        data.append("\"userId\": \"");
        data.append(userId);
        data.append("\"");
        data.append('}');
        channel.publish(localSession, data.toString());
      }
    }
  }

  /**
   * Channels as string.
   *
   * @param channels the channels
   * @return the string
   */
  protected String channelsAsString(Set<ServerChannel> channels) {
    return channels.stream().map(c -> c.getId()).collect(Collectors.joining(", "));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    // Nothing
  }

  /**
   * Gets the cometd server path.
   *
   * @return the cometd server path
   */
  public String getCometdServerPath() {
    return new StringBuilder("/").append(exoBayeux.getCometdContextName()).append("/cometd").toString();
  }

  /**
   * Gets the user token.
   *
   * @param userId the userId
   * @return the token
   */
  public String getUserToken(String userId) {
    return exoBayeux.getUserToken(userId);
  }

  /**
   * Create a new thread executor service.
   *
   * @param threadNamePrefix the thread name prefix
   * @param maxFactor - max processes per CPU core
   * @param queueFactor - queue size per CPU core
   * @return the executor service
   */
  protected ExecutorService createThreadExecutor(String threadNamePrefix, int maxFactor, int queueFactor) {
    // Executor will queue all commands and run them in maximum set of threads.
    // Minimum set of threads will be
    // maintained online even idle, other inactive will be stopped in two
    // minutes.
    final int cpus = Runtime.getRuntime().availableProcessors();
    int poolThreads = cpus / 4;
    poolThreads = poolThreads < MIN_THREADS ? MIN_THREADS : poolThreads;
    int maxThreads = Math.round(cpus * 1f * maxFactor);
    maxThreads = maxThreads > 0 ? maxThreads : 1;
    maxThreads = maxThreads < MIN_MAX_THREADS ? MIN_MAX_THREADS : maxThreads;
    int queueSize = cpus * queueFactor;
    queueSize = queueSize < queueFactor ? queueFactor : queueSize;
    if (LOG.isDebugEnabled()) {
      LOG.debug("Creating thread executor " + threadNamePrefix + "* for " + poolThreads + ".." + maxThreads
          + " threads, queue size " + queueSize);
    }
    return new ThreadPoolExecutor(poolThreads,
                                  maxThreads,
                                  THREAD_IDLE_TIME,
                                  TimeUnit.SECONDS,
                                  new LinkedBlockingQueue<Runnable>(queueSize),
                                  new CommandThreadFactory(threadNamePrefix),
                                  new ThreadPoolExecutor.CallerRunsPolicy());
  }
}
