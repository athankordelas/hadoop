/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;

import org.apache.hadoop.fs.Options;
import org.junit.Assert;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Options.ChecksumOpt;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.SerializedException;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.DrainDispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.nodemanager.ContainerExecutor;
import org.apache.hadoop.yarn.server.nodemanager.DeletionService;
import org.apache.hadoop.yarn.server.nodemanager.LocalDirsHandlerService;
import org.apache.hadoop.yarn.server.nodemanager.NodeManager.NMContext;
import org.apache.hadoop.yarn.server.nodemanager.api.ResourceLocalizationSpec;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalResourceStatus;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalizerAction;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalizerHeartbeatResponse;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.LocalizerStatus;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.ResourceStatusType;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.impl.pb.LocalResourceStatusPBImpl;
import org.apache.hadoop.yarn.server.nodemanager.api.protocolrecords.impl.pb.LocalizerStatusPBImpl;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.application.Application;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.application.ApplicationEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.application.ApplicationEventType;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.application.ApplicationImpl;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerEventType;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerImpl;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerResourceFailedEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ResourceLocalizationService.LocalizerRunner;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ResourceLocalizationService.LocalizerTracker;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ResourceLocalizationService.PublicLocalizer;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ApplicationLocalizationEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ContainerLocalizationCleanupEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ContainerLocalizationEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ContainerLocalizationRequestEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizationEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizationEventType;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizerEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizerEventType;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.LocalizerResourceRequestEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceFailedLocalizationEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.event.ResourceLocalizedEvent;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMMemoryStateStoreService;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMNullStateStoreService;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMStateStoreService;
import org.apache.hadoop.yarn.server.nodemanager.security.NMContainerTokenSecretManager;
import org.apache.hadoop.yarn.server.nodemanager.security.NMTokenSecretManagerInNM;
import org.apache.hadoop.yarn.server.security.ApplicationACLsManager;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestResourceLocalizationService {

  static final Path basedir =
      new Path("target", TestResourceLocalizationService.class.getName());
  static Server mockServer;

  private Configuration conf;
  private AbstractFileSystem spylfs;
  private FileContext lfs;
  private NMContext nmContext;
  @BeforeClass
  public static void setupClass() {
    mockServer = mock(Server.class);
    doReturn(new InetSocketAddress(123)).when(mockServer).getListenerAddress();
  }

  @Before
  public void setup() throws IOException {
    conf = new Configuration();
    spylfs = spy(FileContext.getLocalFSFileContext().getDefaultFileSystem());
    lfs = FileContext.getFileContext(spylfs, conf);

    String logDir = lfs.makeQualified(new Path(basedir, "logdir ")).toString();
    conf.set(YarnConfiguration.NM_LOG_DIRS, logDir);
    nmContext = new NMContext(new NMContainerTokenSecretManager(
      conf), new NMTokenSecretManagerInNM(), null,
      new ApplicationACLsManager(conf), new NMNullStateStoreService());
  }

  @After
  public void cleanup() throws IOException {
    conf = null;
    FileUtils.deleteDirectory(new File(basedir.toString()));
  }
  
  @Test
  public void testLocalizationInit() throws Exception {
    conf.set(CommonConfigurationKeys.FS_PERMISSIONS_UMASK_KEY, "077");
    AsyncDispatcher dispatcher = new AsyncDispatcher();
    dispatcher.init(new Configuration());

    ContainerExecutor exec = mock(ContainerExecutor.class);
    DeletionService delService = spy(new DeletionService(exec));
    delService.init(conf);
    delService.start();

    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

    LocalDirsHandlerService diskhandler = new LocalDirsHandlerService();
    diskhandler.init(conf);

    ResourceLocalizationService locService =
      spy(new ResourceLocalizationService(dispatcher, exec, delService,
                                          diskhandler, nmContext));
    doReturn(lfs)
      .when(locService).getLocalFileContext(isA(Configuration.class));
    try {
      dispatcher.start();

      // initialize ResourceLocalizationService
      locService.init(conf);

      final FsPermission defaultPerm = new FsPermission((short)0755);

      // verify directory creation
      for (Path p : localDirs) {
        p = new Path((new URI(p.toString())).getPath());
        Path usercache = new Path(p, ContainerLocalizer.USERCACHE);
        verify(spylfs)
          .mkdir(eq(usercache),
              eq(defaultPerm), eq(true));
        Path publicCache = new Path(p, ContainerLocalizer.FILECACHE);
        verify(spylfs)
          .mkdir(eq(publicCache),
              eq(defaultPerm), eq(true));
        Path nmPriv = new Path(p, ResourceLocalizationService.NM_PRIVATE_DIR);
        verify(spylfs).mkdir(eq(nmPriv),
            eq(ResourceLocalizationService.NM_PRIVATE_PERM), eq(true));
      }
    } finally {
      dispatcher.stop();
      delService.stop();
    }
  }

  @Test
  public void testDirectoryCleanupOnNewlyCreatedStateStore()
      throws IOException, URISyntaxException {
    conf.set(CommonConfigurationKeys.FS_PERMISSIONS_UMASK_KEY, "077");
    AsyncDispatcher dispatcher = new AsyncDispatcher();
    dispatcher.init(new Configuration());

    ContainerExecutor exec = mock(ContainerExecutor.class);
    DeletionService delService = spy(new DeletionService(exec));
    delService.init(conf);
    delService.start();

    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

    LocalDirsHandlerService diskhandler = new LocalDirsHandlerService();
    diskhandler.init(conf);

    NMStateStoreService nmStateStoreService = mock(NMStateStoreService.class);
    when(nmStateStoreService.canRecover()).thenReturn(true);
    when(nmStateStoreService.isNewlyCreated()).thenReturn(true);

    ResourceLocalizationService locService =
        spy(new ResourceLocalizationService(dispatcher, exec, delService,
            diskhandler,nmContext));
    doReturn(lfs)
        .when(locService).getLocalFileContext(isA(Configuration.class));
    try {
      dispatcher.start();

      // initialize ResourceLocalizationService
      locService.init(conf);

      final FsPermission defaultPerm = new FsPermission((short)0755);

      // verify directory creation
      for (Path p : localDirs) {
        p = new Path((new URI(p.toString())).getPath());
        Path usercache = new Path(p, ContainerLocalizer.USERCACHE);
        verify(spylfs)
            .rename(eq(usercache), any(Path.class), any(Options.Rename.class));
        verify(spylfs)
            .mkdir(eq(usercache),
                eq(defaultPerm), eq(true));
        Path publicCache = new Path(p, ContainerLocalizer.FILECACHE);
        verify(spylfs)
            .rename(eq(usercache), any(Path.class), any(Options.Rename.class));
        verify(spylfs)
            .mkdir(eq(publicCache),
                eq(defaultPerm), eq(true));
        Path nmPriv = new Path(p, ResourceLocalizationService.NM_PRIVATE_DIR);
        verify(spylfs)
            .rename(eq(usercache), any(Path.class), any(Options.Rename.class));
        verify(spylfs).mkdir(eq(nmPriv),
            eq(ResourceLocalizationService.NM_PRIVATE_PERM), eq(true));
      }
    } finally {
      dispatcher.stop();
      delService.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked") // mocked generics
  public void testResourceRelease() throws Exception {
    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

    LocalizerTracker mockLocallilzerTracker = mock(LocalizerTracker.class);
    DrainDispatcher dispatcher = new DrainDispatcher();
    dispatcher.init(conf);
    dispatcher.start();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);
    //Ignore actual localization
    EventHandler<LocalizerEvent> localizerBus = mock(EventHandler.class);
    dispatcher.register(LocalizerEventType.class, localizerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    dirsHandler.init(conf);

    DeletionService delService = new DeletionService(exec);
    delService.init(new Configuration());
    delService.start();

    ResourceLocalizationService rawService =
      new ResourceLocalizationService(dispatcher, exec, delService,
                                      dirsHandler, nmContext);
    ResourceLocalizationService spyService = spy(rawService);
    doReturn(mockServer).when(spyService).createServer();
    doReturn(mockLocallilzerTracker).when(spyService).createLocalizerTracker(
        isA(Configuration.class));
    doReturn(lfs).when(spyService)
        .getLocalFileContext(isA(Configuration.class));
    try {
      spyService.init(conf);
      spyService.start();

      final String user = "user0";
      // init application
      final Application app = mock(Application.class);
      final ApplicationId appId =
          BuilderUtils.newApplicationId(314159265358979L, 3);
      when(app.getUser()).thenReturn(user);
      when(app.getAppId()).thenReturn(appId);
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      dispatcher.await();
            
      //Get a handle on the trackers after they're setup with INIT_APP_RESOURCES
      LocalResourcesTracker appTracker =
          spyService.getLocalResourcesTracker(
              LocalResourceVisibility.APPLICATION, user, appId);
      LocalResourcesTracker privTracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PRIVATE,
              user, appId);
      LocalResourcesTracker pubTracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC,
              user, appId);

      // init container.
      final Container c = getMockContainer(appId, 42, user);
      
      // init resources
      Random r = new Random();
      long seed = r.nextLong();
      System.out.println("SEED: " + seed);
      r.setSeed(seed);
      
      // Send localization requests for one resource of each type.
      final LocalResource privResource = getPrivateMockedResource(r);
      final LocalResourceRequest privReq =
          new LocalResourceRequest(privResource);
      
      final LocalResource pubResource = getPublicMockedResource(r);
      final LocalResourceRequest pubReq = new LocalResourceRequest(pubResource);
      final LocalResource pubResource2 = getPublicMockedResource(r);
      final LocalResourceRequest pubReq2 =
          new LocalResourceRequest(pubResource2);
      
      final LocalResource appResource = getAppMockedResource(r);
      final LocalResourceRequest appReq = new LocalResourceRequest(appResource);
      
      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req =
          new HashMap<LocalResourceVisibility, 
                      Collection<LocalResourceRequest>>();
      req.put(LocalResourceVisibility.PRIVATE,
          Collections.singletonList(privReq));
      req.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq));
      req.put(LocalResourceVisibility.APPLICATION,
          Collections.singletonList(appReq));
      
      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req2 =
        new HashMap<LocalResourceVisibility, 
                    Collection<LocalResourceRequest>>();
      req2.put(LocalResourceVisibility.PRIVATE,
          Collections.singletonList(privReq));
      req2.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq2));
      
      Set<LocalResourceRequest> pubRsrcs = new HashSet<LocalResourceRequest>();
      pubRsrcs.add(pubReq);
      pubRsrcs.add(pubReq2);
      
      // Send Request event
      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      spyService.handle(new ContainerLocalizationRequestEvent(c, req2));
      dispatcher.await();

      int privRsrcCount = 0;
      for (LocalizedResource lr : privTracker) {
        privRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 2, lr.getRefCount());
        Assert.assertEquals(privReq, lr.getRequest());
      }
      Assert.assertEquals(1, privRsrcCount);

      int pubRsrcCount = 0;
      for (LocalizedResource lr : pubTracker) {
        pubRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 1, lr.getRefCount());
        pubRsrcs.remove(lr.getRequest());
      }
      Assert.assertEquals(0, pubRsrcs.size());
      Assert.assertEquals(2, pubRsrcCount);

      int appRsrcCount = 0;
      for (LocalizedResource lr : appTracker) {
        appRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 1, lr.getRefCount());
        Assert.assertEquals(appReq, lr.getRequest());
      }
      Assert.assertEquals(1, appRsrcCount);
      
      //Send Cleanup Event
      spyService.handle(new ContainerLocalizationCleanupEvent(c, req));
      verify(mockLocallilzerTracker)
        .cleanupPrivLocalizers("container_314159265358979_0003_01_000042");
      req2.remove(LocalResourceVisibility.PRIVATE);
      spyService.handle(new ContainerLocalizationCleanupEvent(c, req2));
      dispatcher.await();
      
      pubRsrcs.add(pubReq);
      pubRsrcs.add(pubReq2);

      privRsrcCount = 0;
      for (LocalizedResource lr : privTracker) {
        privRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 1, lr.getRefCount());
        Assert.assertEquals(privReq, lr.getRequest());
      }
      Assert.assertEquals(1, privRsrcCount);

      pubRsrcCount = 0;
      for (LocalizedResource lr : pubTracker) {
        pubRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 0, lr.getRefCount());
        pubRsrcs.remove(lr.getRequest());
      }
      Assert.assertEquals(0, pubRsrcs.size());
      Assert.assertEquals(2, pubRsrcCount);

      appRsrcCount = 0;
      for (LocalizedResource lr : appTracker) {
        appRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 0, lr.getRefCount());
        Assert.assertEquals(appReq, lr.getRequest());
      }
      Assert.assertEquals(1, appRsrcCount);
    } finally {
      dispatcher.stop();
      delService.stop();
    }
  }
  
  @Test
  @SuppressWarnings("unchecked") // mocked generics
  public void testRecovery() throws Exception {
    final String user1 = "user1";
    final String user2 = "user2";
    final ApplicationId appId1 = ApplicationId.newInstance(1, 1);
    final ApplicationId appId2 = ApplicationId.newInstance(1, 2);

    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);
    conf.setBoolean(YarnConfiguration.NM_RECOVERY_ENABLED, true);

    NMMemoryStateStoreService stateStore = new NMMemoryStateStoreService();
    stateStore.init(conf);
    stateStore.start();
    DrainDispatcher dispatcher = new DrainDispatcher();
    dispatcher.init(conf);
    dispatcher.start();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);
    //Ignore actual localization
    EventHandler<LocalizerEvent> localizerBus = mock(EventHandler.class);
    dispatcher.register(LocalizerEventType.class, localizerBus);

    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    dirsHandler.init(conf);

    ResourceLocalizationService spyService =
        createSpyService(dispatcher, dirsHandler, stateStore);
    try {
      spyService.init(conf);
      spyService.start();

      final Application app1 = mock(Application.class);
      when(app1.getUser()).thenReturn(user1);
      when(app1.getAppId()).thenReturn(appId1);
      final Application app2 = mock(Application.class);
      when(app2.getUser()).thenReturn(user2);
      when(app2.getAppId()).thenReturn(appId2);
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app1));
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app2));
      dispatcher.await();

      //Get a handle on the trackers after they're setup with INIT_APP_RESOURCES
      LocalResourcesTracker appTracker1 =
          spyService.getLocalResourcesTracker(
              LocalResourceVisibility.APPLICATION, user1, appId1);
      LocalResourcesTracker privTracker1 =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PRIVATE,
              user1, null);
      LocalResourcesTracker appTracker2 =
          spyService.getLocalResourcesTracker(
              LocalResourceVisibility.APPLICATION, user2, appId2);
      LocalResourcesTracker pubTracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC,
              null, null);

      // init containers
      final Container c1 = getMockContainer(appId1, 1, user1);
      final Container c2 = getMockContainer(appId2, 2, user2);

      // init resources
      Random r = new Random();
      long seed = r.nextLong();
      System.out.println("SEED: " + seed);
      r.setSeed(seed);

      // Send localization requests of each type.
      final LocalResource privResource1 = getPrivateMockedResource(r);
      final LocalResourceRequest privReq1 =
          new LocalResourceRequest(privResource1);
      final LocalResource privResource2 = getPrivateMockedResource(r);
      final LocalResourceRequest privReq2 =
          new LocalResourceRequest(privResource2);

      final LocalResource pubResource1 = getPublicMockedResource(r);
      final LocalResourceRequest pubReq1 =
          new LocalResourceRequest(pubResource1);
      final LocalResource pubResource2 = getPublicMockedResource(r);
      final LocalResourceRequest pubReq2 =
          new LocalResourceRequest(pubResource2);

      final LocalResource appResource1 = getAppMockedResource(r);
      final LocalResourceRequest appReq1 =
          new LocalResourceRequest(appResource1);
      final LocalResource appResource2 = getAppMockedResource(r);
      final LocalResourceRequest appReq2 =
          new LocalResourceRequest(appResource2);
      final LocalResource appResource3 = getAppMockedResource(r);
      final LocalResourceRequest appReq3 =
          new LocalResourceRequest(appResource3);

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req1 =
          new HashMap<LocalResourceVisibility,
                      Collection<LocalResourceRequest>>();
      req1.put(LocalResourceVisibility.PRIVATE,
          Arrays.asList(new LocalResourceRequest[] { privReq1, privReq2 }));
      req1.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq1));
      req1.put(LocalResourceVisibility.APPLICATION,
          Collections.singletonList(appReq1));

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req2 =
        new HashMap<LocalResourceVisibility,
                    Collection<LocalResourceRequest>>();
      req2.put(LocalResourceVisibility.APPLICATION,
          Arrays.asList(new LocalResourceRequest[] { appReq2, appReq3 }));
      req2.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq2));

      // Send Request event
      spyService.handle(new ContainerLocalizationRequestEvent(c1, req1));
      spyService.handle(new ContainerLocalizationRequestEvent(c2, req2));
      dispatcher.await();

      // Simulate start of localization for all resources
      privTracker1.getPathForLocalization(privReq1,
          dirsHandler.getLocalPathForWrite(
              ContainerLocalizer.USERCACHE + user1), null);
      privTracker1.getPathForLocalization(privReq2,
          dirsHandler.getLocalPathForWrite(
              ContainerLocalizer.USERCACHE + user1), null);
      LocalizedResource privLr1 = privTracker1.getLocalizedResource(privReq1);
      LocalizedResource privLr2 = privTracker1.getLocalizedResource(privReq2);
      appTracker1.getPathForLocalization(appReq1,
          dirsHandler.getLocalPathForWrite(
              ContainerLocalizer.APPCACHE + appId1), null);
      LocalizedResource appLr1 = appTracker1.getLocalizedResource(appReq1);
      appTracker2.getPathForLocalization(appReq2,
          dirsHandler.getLocalPathForWrite(
              ContainerLocalizer.APPCACHE + appId2), null);
      LocalizedResource appLr2 = appTracker2.getLocalizedResource(appReq2);
      appTracker2.getPathForLocalization(appReq3,
          dirsHandler.getLocalPathForWrite(
              ContainerLocalizer.APPCACHE + appId2), null);
      LocalizedResource appLr3 = appTracker2.getLocalizedResource(appReq3);
      pubTracker.getPathForLocalization(pubReq1,
          dirsHandler.getLocalPathForWrite(ContainerLocalizer.FILECACHE),
          null);
      LocalizedResource pubLr1 = pubTracker.getLocalizedResource(pubReq1);
      pubTracker.getPathForLocalization(pubReq2,
          dirsHandler.getLocalPathForWrite(ContainerLocalizer.FILECACHE),
          null);
      LocalizedResource pubLr2 = pubTracker.getLocalizedResource(pubReq2);

      // Simulate completion of localization for most resources with
      // possibly different sizes than in the request
      assertNotNull("Localization not started", privLr1.getLocalPath());
      privTracker1.handle(new ResourceLocalizedEvent(privReq1,
          privLr1.getLocalPath(), privLr1.getSize() + 5));
      assertNotNull("Localization not started", privLr2.getLocalPath());
      privTracker1.handle(new ResourceLocalizedEvent(privReq2,
          privLr2.getLocalPath(), privLr2.getSize() + 10));
      assertNotNull("Localization not started", appLr1.getLocalPath());
      appTracker1.handle(new ResourceLocalizedEvent(appReq1,
          appLr1.getLocalPath(), appLr1.getSize()));
      assertNotNull("Localization not started", appLr3.getLocalPath());
      appTracker2.handle(new ResourceLocalizedEvent(appReq3,
          appLr3.getLocalPath(), appLr3.getSize() + 7));
      assertNotNull("Localization not started", pubLr1.getLocalPath());
      pubTracker.handle(new ResourceLocalizedEvent(pubReq1,
          pubLr1.getLocalPath(), pubLr1.getSize() + 1000));
      assertNotNull("Localization not started", pubLr2.getLocalPath());
      pubTracker.handle(new ResourceLocalizedEvent(pubReq2,
          pubLr2.getLocalPath(), pubLr2.getSize() + 99999));

      dispatcher.await();
      assertEquals(ResourceState.LOCALIZED, privLr1.getState());
      assertEquals(ResourceState.LOCALIZED, privLr2.getState());
      assertEquals(ResourceState.LOCALIZED, appLr1.getState());
      assertEquals(ResourceState.DOWNLOADING, appLr2.getState());
      assertEquals(ResourceState.LOCALIZED, appLr3.getState());
      assertEquals(ResourceState.LOCALIZED, pubLr1.getState());
      assertEquals(ResourceState.LOCALIZED, pubLr2.getState());

      // restart and recover
      spyService = createSpyService(dispatcher, dirsHandler, stateStore);
      spyService.init(conf);
      spyService.recoverLocalizedResources(
          stateStore.loadLocalizationState());
      dispatcher.await();

      appTracker1 = spyService.getLocalResourcesTracker(
              LocalResourceVisibility.APPLICATION, user1, appId1);
      privTracker1 = spyService.getLocalResourcesTracker(
          LocalResourceVisibility.PRIVATE, user1, null);
      appTracker2 = spyService.getLocalResourcesTracker(
              LocalResourceVisibility.APPLICATION, user2, appId2);
      pubTracker = spyService.getLocalResourcesTracker(
          LocalResourceVisibility.PUBLIC, null, null);

      LocalizedResource recoveredRsrc =
          privTracker1.getLocalizedResource(privReq1);
      assertEquals(privReq1, recoveredRsrc.getRequest());
      assertEquals(privLr1.getLocalPath(), recoveredRsrc.getLocalPath());
      assertEquals(privLr1.getSize(), recoveredRsrc.getSize());
      assertEquals(ResourceState.LOCALIZED, recoveredRsrc.getState());
      recoveredRsrc = privTracker1.getLocalizedResource(privReq2);
      assertEquals(privReq2, recoveredRsrc.getRequest());
      assertEquals(privLr2.getLocalPath(), recoveredRsrc.getLocalPath());
      assertEquals(privLr2.getSize(), recoveredRsrc.getSize());
      assertEquals(ResourceState.LOCALIZED, recoveredRsrc.getState());
      recoveredRsrc = appTracker1.getLocalizedResource(appReq1);
      assertEquals(appReq1, recoveredRsrc.getRequest());
      assertEquals(appLr1.getLocalPath(), recoveredRsrc.getLocalPath());
      assertEquals(appLr1.getSize(), recoveredRsrc.getSize());
      assertEquals(ResourceState.LOCALIZED, recoveredRsrc.getState());
      recoveredRsrc = appTracker2.getLocalizedResource(appReq2);
      assertNull("in-progress resource should not be present", recoveredRsrc);
      recoveredRsrc = appTracker2.getLocalizedResource(appReq3);
      assertEquals(appReq3, recoveredRsrc.getRequest());
      assertEquals(appLr3.getLocalPath(), recoveredRsrc.getLocalPath());
      assertEquals(appLr3.getSize(), recoveredRsrc.getSize());
      assertEquals(ResourceState.LOCALIZED, recoveredRsrc.getState());
    } finally {
      dispatcher.stop();
      stateStore.close();
    }
  }
  

  @Test( timeout = 10000)
  @SuppressWarnings("unchecked") // mocked generics
  public void testLocalizerRunnerException() throws Exception {
    DrainDispatcher dispatcher = new DrainDispatcher();
    dispatcher.init(conf);
    dispatcher.start();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    LocalDirsHandlerService dirsHandlerSpy = spy(dirsHandler);
    dirsHandlerSpy.init(conf);

    DeletionService delServiceReal = new DeletionService(exec);
    DeletionService delService = spy(delServiceReal);
    delService.init(new Configuration());
    delService.start();

    ResourceLocalizationService rawService =
        new ResourceLocalizationService(dispatcher, exec, delService,
        dirsHandlerSpy, nmContext);
    ResourceLocalizationService spyService = spy(rawService);
    doReturn(mockServer).when(spyService).createServer();
    try {
      spyService.init(conf);
      spyService.start();

      // init application
      final Application app = mock(Application.class);
      final ApplicationId appId =
          BuilderUtils.newApplicationId(314159265358979L, 3);
      when(app.getUser()).thenReturn("user0");
      when(app.getAppId()).thenReturn(appId);
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      dispatcher.await();

      Random r = new Random();
      long seed = r.nextLong();
      System.out.println("SEED: " + seed);
      r.setSeed(seed);
      final Container c = getMockContainer(appId, 42, "user0");
      final LocalResource resource1 = getPrivateMockedResource(r);
      System.out.println("Here 4");
      
      final LocalResourceRequest req1 = new LocalResourceRequest(resource1);
      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> rsrcs =
        new HashMap<LocalResourceVisibility, 
                    Collection<LocalResourceRequest>>();
      List<LocalResourceRequest> privateResourceList =
          new ArrayList<LocalResourceRequest>();
      privateResourceList.add(req1);
      rsrcs.put(LocalResourceVisibility.PRIVATE, privateResourceList);

      final Constructor<?>[] constructors =
          FSError.class.getDeclaredConstructors();
      constructors[0].setAccessible(true);
      FSError fsError =
          (FSError) constructors[0].newInstance(new IOException("Disk Error"));

      Mockito
        .doThrow(fsError)
        .when(dirsHandlerSpy)
        .getLocalPathForWrite(isA(String.class));
      spyService.handle(new ContainerLocalizationRequestEvent(c, rsrcs));
      Thread.sleep(1000);
      dispatcher.await();
      // Verify if ContainerResourceFailedEvent is invoked on FSError
      verify(containerBus).handle(isA(ContainerResourceFailedEvent.class));
    } finally {
      spyService.stop();
      dispatcher.stop();
      delService.stop();
    }
  }

  @Test( timeout = 10000)
  @SuppressWarnings("unchecked") // mocked generics
  public void testLocalizationHeartbeat() throws Exception {
    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[1];
    // Making sure that we have only one local disk so that it will only be
    // selected for consecutive resource localization calls.  This is required
    // to test LocalCacheDirectoryManager.
    localDirs.add(lfs.makeQualified(new Path(basedir, 0 + "")));
    sDirs[0] = localDirs.get(0).toString();

    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);
    // Adding configuration to make sure there is only one file per
    // directory
    conf.set(YarnConfiguration.NM_LOCAL_CACHE_MAX_FILES_PER_DIRECTORY, "37");
    DrainDispatcher dispatcher = new DrainDispatcher();
    dispatcher.init(conf);
    dispatcher.start();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    dirsHandler.init(conf);

    DeletionService delServiceReal = new DeletionService(exec);
    DeletionService delService = spy(delServiceReal);
    delService.init(new Configuration());
    delService.start();

    ResourceLocalizationService rawService =
      new ResourceLocalizationService(dispatcher, exec, delService,
                                      dirsHandler, nmContext);
    ResourceLocalizationService spyService = spy(rawService);
    doReturn(mockServer).when(spyService).createServer();
    doReturn(lfs).when(spyService).getLocalFileContext(isA(Configuration.class));
    FsPermission defaultPermission =
        FsPermission.getDirDefault().applyUMask(lfs.getUMask());
    FsPermission nmPermission =
        ResourceLocalizationService.NM_PRIVATE_PERM.applyUMask(lfs.getUMask());
    final Path userDir =
        new Path(sDirs[0].substring("file:".length()),
          ContainerLocalizer.USERCACHE);
    final Path fileDir =
        new Path(sDirs[0].substring("file:".length()),
          ContainerLocalizer.FILECACHE);
    final Path sysDir =
        new Path(sDirs[0].substring("file:".length()),
          ResourceLocalizationService.NM_PRIVATE_DIR);
    final FileStatus fs =
        new FileStatus(0, true, 1, 0, System.currentTimeMillis(), 0,
          defaultPermission, "", "", new Path(sDirs[0]));
    final FileStatus nmFs =
        new FileStatus(0, true, 1, 0, System.currentTimeMillis(), 0,
          nmPermission, "", "", sysDir);

    doAnswer(new Answer<FileStatus>() {
      @Override
      public FileStatus answer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        if (args.length > 0) {
          if (args[0].equals(userDir) || args[0].equals(fileDir)) {
            return fs;
          }
        }
        return nmFs;
      }
    }).when(spylfs).getFileStatus(isA(Path.class));

    try {
      spyService.init(conf);
      spyService.start();

      // init application
      final Application app = mock(Application.class);
      final ApplicationId appId =
          BuilderUtils.newApplicationId(314159265358979L, 3);
      when(app.getUser()).thenReturn("user0");
      when(app.getAppId()).thenReturn(appId);
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      ArgumentMatcher<ApplicationEvent> matchesAppInit =
        new ArgumentMatcher<ApplicationEvent>() {
          @Override
          public boolean matches(Object o) {
            ApplicationEvent evt = (ApplicationEvent) o;
            return evt.getType() == ApplicationEventType.APPLICATION_INITED
              && appId == evt.getApplicationID();
          }
        };
      dispatcher.await();
      verify(applicationBus).handle(argThat(matchesAppInit));

      // init container rsrc, localizer
      Random r = new Random();
      long seed = r.nextLong();
      System.out.println("SEED: " + seed);
      r.setSeed(seed);
      final Container c = getMockContainer(appId, 42, "user0");
      FSDataOutputStream out =
        new FSDataOutputStream(new DataOutputBuffer(), null);
      doReturn(out).when(spylfs).createInternal(isA(Path.class),
          isA(EnumSet.class), isA(FsPermission.class), anyInt(), anyShort(),
          anyLong(), isA(Progressable.class), isA(ChecksumOpt.class), anyBoolean());
      final LocalResource resource1 = getPrivateMockedResource(r);
      LocalResource resource2 = null;
      do {
        resource2 = getPrivateMockedResource(r);
      } while (resource2 == null || resource2.equals(resource1));
      LocalResource resource3 = null;
      do {
        resource3 = getPrivateMockedResource(r);
      } while (resource3 == null || resource3.equals(resource1)
          || resource3.equals(resource2));
      // above call to make sure we don't get identical resources.
      
      final LocalResourceRequest req1 = new LocalResourceRequest(resource1);
      final LocalResourceRequest req2 = new LocalResourceRequest(resource2);
      final LocalResourceRequest req3 = new LocalResourceRequest(resource3);
      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> rsrcs =
        new HashMap<LocalResourceVisibility, 
                    Collection<LocalResourceRequest>>();
      List<LocalResourceRequest> privateResourceList =
          new ArrayList<LocalResourceRequest>();
      privateResourceList.add(req1);
      privateResourceList.add(req2);
      privateResourceList.add(req3);
      rsrcs.put(LocalResourceVisibility.PRIVATE, privateResourceList);
      spyService.handle(new ContainerLocalizationRequestEvent(c, rsrcs));
      // Sigh. Thread init of private localizer not accessible
      Thread.sleep(1000);
      dispatcher.await();
      String appStr = ConverterUtils.toString(appId);
      String ctnrStr = c.getContainerId().toString();
      ArgumentCaptor<Path> tokenPathCaptor = ArgumentCaptor.forClass(Path.class);
      verify(exec).startLocalizer(tokenPathCaptor.capture(),
          isA(InetSocketAddress.class), eq("user0"), eq(appStr), eq(ctnrStr),
          isA(LocalDirsHandlerService.class));
      Path localizationTokenPath = tokenPathCaptor.getValue();

      // heartbeat from localizer
      LocalResourceStatus rsrc1success = mock(LocalResourceStatus.class);
      LocalResourceStatus rsrc2pending = mock(LocalResourceStatus.class);
      LocalResourceStatus rsrc2success = mock(LocalResourceStatus.class);
      LocalResourceStatus rsrc3success = mock(LocalResourceStatus.class);
      LocalizerStatus stat = mock(LocalizerStatus.class);
      when(stat.getLocalizerId()).thenReturn(ctnrStr);
      when(rsrc1success.getResource()).thenReturn(resource1);
      when(rsrc2pending.getResource()).thenReturn(resource2);
      when(rsrc2success.getResource()).thenReturn(resource2);
      when(rsrc3success.getResource()).thenReturn(resource3);
      when(rsrc1success.getLocalSize()).thenReturn(4344L);
      when(rsrc2success.getLocalSize()).thenReturn(2342L);
      when(rsrc3success.getLocalSize()).thenReturn(5345L);
      URL locPath = getPath("/cache/private/blah");
      when(rsrc1success.getLocalPath()).thenReturn(locPath);
      when(rsrc2success.getLocalPath()).thenReturn(locPath);
      when(rsrc3success.getLocalPath()).thenReturn(locPath);
      when(rsrc1success.getStatus()).thenReturn(ResourceStatusType.FETCH_SUCCESS);
      when(rsrc2pending.getStatus()).thenReturn(ResourceStatusType.FETCH_PENDING);
      when(rsrc2success.getStatus()).thenReturn(ResourceStatusType.FETCH_SUCCESS);
      when(rsrc3success.getStatus()).thenReturn(ResourceStatusType.FETCH_SUCCESS);

      // Four heartbeats with sending:
      // 1 - empty
      // 2 - resource1 FETCH_SUCCESS
      // 3 - resource2 FETCH_PENDING
      // 4 - resource2 FETCH_SUCCESS, resource3 FETCH_SUCCESS
      List<LocalResourceStatus> rsrcs4 = new ArrayList<LocalResourceStatus>();
      rsrcs4.add(rsrc2success);
      rsrcs4.add(rsrc3success);
      when(stat.getResources())
        .thenReturn(Collections.<LocalResourceStatus>emptyList())
        .thenReturn(Collections.singletonList(rsrc1success))
        .thenReturn(Collections.singletonList(rsrc2pending))
        .thenReturn(rsrcs4)
        .thenReturn(Collections.<LocalResourceStatus>emptyList());

      String localPath = Path.SEPARATOR + ContainerLocalizer.USERCACHE +
          Path.SEPARATOR + "user0" + Path.SEPARATOR +
          ContainerLocalizer.FILECACHE;

      // First heartbeat
      LocalizerHeartbeatResponse response = spyService.heartbeat(stat);
      assertEquals(LocalizerAction.LIVE, response.getLocalizerAction());
      assertEquals(1, response.getResourceSpecs().size());
      assertEquals(req1,
        new LocalResourceRequest(response.getResourceSpecs().get(0).getResource()));
      URL localizedPath =
          response.getResourceSpecs().get(0).getDestinationDirectory();
      // Appending to local path unique number(10) generated as a part of
      // LocalResourcesTracker
      assertTrue(localizedPath.getFile().endsWith(
        localPath + Path.SEPARATOR + "10"));

      // Second heartbeat
      response = spyService.heartbeat(stat);
      assertEquals(LocalizerAction.LIVE, response.getLocalizerAction());
      assertEquals(1, response.getResourceSpecs().size());
      assertEquals(req2, new LocalResourceRequest(response.getResourceSpecs()
        .get(0).getResource()));
      localizedPath =
          response.getResourceSpecs().get(0).getDestinationDirectory();
      // Resource's destination path should be now inside sub directory 0 as
      // LocalCacheDirectoryManager will be used and we have restricted number
      // of files per directory to 1.
      assertTrue(localizedPath.getFile().endsWith(
        localPath + Path.SEPARATOR + "0" + Path.SEPARATOR + "11"));

      // Third heartbeat
      response = spyService.heartbeat(stat);
      assertEquals(LocalizerAction.LIVE, response.getLocalizerAction());
      assertEquals(1, response.getResourceSpecs().size());
      assertEquals(req3, new LocalResourceRequest(response.getResourceSpecs()
          .get(0).getResource()));
      localizedPath =
          response.getResourceSpecs().get(0).getDestinationDirectory();
      assertTrue(localizedPath.getFile().endsWith(
          localPath + Path.SEPARATOR + "1" + Path.SEPARATOR + "12"));

      response = spyService.heartbeat(stat);
      assertEquals(LocalizerAction.LIVE, response.getLocalizerAction());

      spyService.handle(new ContainerLocalizationEvent(
          LocalizationEventType.CONTAINER_RESOURCES_LOCALIZED, c));

      // get shutdown after receive CONTAINER_RESOURCES_LOCALIZED event
      response = spyService.heartbeat(stat);
      assertEquals(LocalizerAction.DIE, response.getLocalizerAction());

      dispatcher.await();
      // verify container notification
      ArgumentMatcher<ContainerEvent> matchesContainerLoc =
        new ArgumentMatcher<ContainerEvent>() {
          @Override
          public boolean matches(Object o) {
            ContainerEvent evt = (ContainerEvent) o;
            return evt.getType() == ContainerEventType.RESOURCE_LOCALIZED
              && c.getContainerId() == evt.getContainerID();
          }
        };
      // total 3 resource localzation calls. one for each resource.
      verify(containerBus, times(3)).handle(argThat(matchesContainerLoc));
        
      // Verify deletion of localization token.
      verify(delService).delete((String)isNull(), eq(localizationTokenPath));
    } finally {
      spyService.stop();
      dispatcher.stop();
      delService.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testPublicResourceInitializesLocalDir() throws Exception {

    // Setup state to simulate restart NM with existing state meaning no
    // directory creation during initialization
    NMStateStoreService spyStateStore = spy(nmContext.getNMStateStore());
    when(spyStateStore.canRecover()).thenReturn(true);
    NMContext spyContext = spy(nmContext);
    when(spyContext.getNMStateStore()).thenReturn(spyStateStore);

    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);


    DrainDispatcher dispatcher = new DrainDispatcher();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    DeletionService delService = mock(DeletionService.class);
    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    dirsHandler.init(conf);

    dispatcher.init(conf);
    dispatcher.start();

    try {
      ResourceLocalizationService rawService =
          new ResourceLocalizationService(dispatcher, exec, delService,
              dirsHandler, spyContext);
      ResourceLocalizationService spyService = spy(rawService);
      doReturn(mockServer).when(spyService).createServer();
      doReturn(lfs).when(spyService).getLocalFileContext(
          isA(Configuration.class));

      spyService.init(conf);
      spyService.start();

      final FsPermission defaultPerm = new FsPermission((short)0755);

      // verify directory is not created at initialization
      for (Path p : localDirs) {
        p = new Path((new URI(p.toString())).getPath());
        Path publicCache = new Path(p, ContainerLocalizer.FILECACHE);
        verify(spylfs, never())
            .mkdir(eq(publicCache),eq(defaultPerm), eq(true));
      }

      final String user = "user0";
      // init application
      final Application app = mock(Application.class);
      final ApplicationId appId =
          BuilderUtils.newApplicationId(314159265358979L, 3);
      when(app.getUser()).thenReturn(user);
      when(app.getAppId()).thenReturn(appId);
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      dispatcher.await();

      // init container.
      final Container c = getMockContainer(appId, 42, user);

      // init resources
      Random r = new Random();
      long seed = r.nextLong();
      System.out.println("SEED: " + seed);
      r.setSeed(seed);

      // Queue up public resource localization
      final LocalResource pubResource = getPublicMockedResource(r);
      final LocalResourceRequest pubReq = new LocalResourceRequest(pubResource);

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req =
          new HashMap<LocalResourceVisibility,
              Collection<LocalResourceRequest>>();
      req.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq));

      Set<LocalResourceRequest> pubRsrcs = new HashSet<LocalResourceRequest>();
      pubRsrcs.add(pubReq);

      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      dispatcher.await();

      // verify directory creation
      for (Path p : localDirs) {
        p = new Path((new URI(p.toString())).getPath());
        Path publicCache = new Path(p, ContainerLocalizer.FILECACHE);
        verify(spylfs).mkdir(eq(publicCache),eq(defaultPerm), eq(true));
      }
    } finally {
      dispatcher.stop();
    }
  }

  @Test(timeout=20000)
  @SuppressWarnings("unchecked") // mocked generics
  public void testFailedPublicResource() throws Exception {
    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

    DrainDispatcher dispatcher = new DrainDispatcher();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    DeletionService delService = mock(DeletionService.class);
    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    dirsHandler.init(conf);

    dispatcher.init(conf);
    dispatcher.start();

    try {
      ResourceLocalizationService rawService =
          new ResourceLocalizationService(dispatcher, exec, delService,
                                        dirsHandler, nmContext);
      ResourceLocalizationService spyService = spy(rawService);
      doReturn(mockServer).when(spyService).createServer();
      doReturn(lfs).when(spyService).getLocalFileContext(
          isA(Configuration.class));

      spyService.init(conf);
      spyService.start();

      final String user = "user0";
      // init application
      final Application app = mock(Application.class);
      final ApplicationId appId =
          BuilderUtils.newApplicationId(314159265358979L, 3);
      when(app.getUser()).thenReturn(user);
      when(app.getAppId()).thenReturn(appId);
      spyService.handle(new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      dispatcher.await();

      // init container.
      final Container c = getMockContainer(appId, 42, user);

      // init resources
      Random r = new Random();
      long seed = r.nextLong();
      System.out.println("SEED: " + seed);
      r.setSeed(seed);

      // cause chmod to fail after a delay
      final CyclicBarrier barrier = new CyclicBarrier(2);
      doAnswer(new Answer<Void>() {
          public Void answer(InvocationOnMock invocation) throws IOException {
            try {
              barrier.await();
            } catch (InterruptedException e) {
            } catch (BrokenBarrierException e) {
            }
            throw new IOException("forced failure");
          }
        }).when(spylfs)
            .setPermission(isA(Path.class), isA(FsPermission.class));

      // Queue up two localization requests for the same public resource
      final LocalResource pubResource = getPublicMockedResource(r);
      final LocalResourceRequest pubReq = new LocalResourceRequest(pubResource);

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req =
          new HashMap<LocalResourceVisibility,
                      Collection<LocalResourceRequest>>();
      req.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq));

      Set<LocalResourceRequest> pubRsrcs = new HashSet<LocalResourceRequest>();
      pubRsrcs.add(pubReq);

      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      dispatcher.await();

      // allow the chmod to fail now that both requests have been queued
      barrier.await();
      verify(containerBus, timeout(5000).times(2))
          .handle(isA(ContainerResourceFailedEvent.class));
    } finally {
      dispatcher.stop();
    }
  }
  
  /*
   * Test case for handling RejectedExecutionException and IOException which can
   * be thrown when adding public resources to the pending queue.
   * RejectedExecutionException can be thrown either due to the incoming queue
   * being full or if the ExecutorCompletionService threadpool is shutdown.
   * Since it's hard to simulate the queue being full, this test just shuts down
   * the threadpool and makes sure the exception is handled. If anything is
   * messed up the async dispatcher thread will cause a system exit causing the
   * test to fail.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testPublicResourceAddResourceExceptions() throws Exception {
    List<Path> localDirs = new ArrayList<Path>();
    String[] sDirs = new String[4];
    for (int i = 0; i < 4; ++i) {
      localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
      sDirs[i] = localDirs.get(i).toString();
    }
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);
    conf.setBoolean(Dispatcher.DISPATCHER_EXIT_ON_ERROR_KEY, true);

    DrainDispatcher dispatcher = new DrainDispatcher();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    DeletionService delService = mock(DeletionService.class);
    LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
    LocalDirsHandlerService dirsHandlerSpy = spy(dirsHandler);
    dirsHandlerSpy.init(conf);

    dispatcher.init(conf);
    dispatcher.start();

    try {
      ResourceLocalizationService rawService =
          new ResourceLocalizationService(dispatcher, exec, delService,
            dirsHandlerSpy, nmContext);
      ResourceLocalizationService spyService = spy(rawService);
      doReturn(mockServer).when(spyService).createServer();
      doReturn(lfs).when(spyService).getLocalFileContext(
        isA(Configuration.class));

      spyService.init(conf);
      spyService.start();

      final String user = "user0";
      // init application
      final Application app = mock(Application.class);
      final ApplicationId appId =
          BuilderUtils.newApplicationId(314159265358979L, 3);
      when(app.getUser()).thenReturn(user);
      when(app.getAppId()).thenReturn(appId);
      spyService.handle(new ApplicationLocalizationEvent(
        LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      dispatcher.await();

      // init resources
      Random r = new Random();
      r.setSeed(r.nextLong());

      // Queue localization request for the public resource
      final LocalResource pubResource = getPublicMockedResource(r);
      final LocalResourceRequest pubReq = new LocalResourceRequest(pubResource);
      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req =
          new HashMap<LocalResourceVisibility, Collection<LocalResourceRequest>>();
      req
        .put(LocalResourceVisibility.PUBLIC, Collections.singletonList(pubReq));

      // init container.
      final Container c = getMockContainer(appId, 42, user);

      // first test ioexception
      Mockito
        .doThrow(new IOException())
        .when(dirsHandlerSpy)
        .getLocalPathForWrite(isA(String.class), Mockito.anyLong(),
          Mockito.anyBoolean());
      // send request
      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      dispatcher.await();
      LocalResourcesTracker tracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC,
            user, appId);
      Assert.assertNull(tracker.getLocalizedResource(pubReq));

      // test IllegalArgumentException
      String name = Long.toHexString(r.nextLong());
      URL url = getPath("/local/PRIVATE/" + name + "/");
      final LocalResource rsrc =
          BuilderUtils.newLocalResource(url, LocalResourceType.FILE,
          LocalResourceVisibility.PUBLIC, r.nextInt(1024) + 1024L,
          r.nextInt(1024) + 2048L, false);
      final LocalResourceRequest pubReq1 = new LocalResourceRequest(rsrc);
      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req1 =
          new HashMap<LocalResourceVisibility, 
          Collection<LocalResourceRequest>>();
      req1.put(LocalResourceVisibility.PUBLIC,
          Collections.singletonList(pubReq1));
      Mockito
        .doCallRealMethod()
        .when(dirsHandlerSpy)
        .getLocalPathForWrite(isA(String.class), Mockito.anyLong(),
          Mockito.anyBoolean());
      // send request
      spyService.handle(new ContainerLocalizationRequestEvent(c, req1));
      dispatcher.await();
      tracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC,
          user, appId);
      Assert.assertNull(tracker.getLocalizedResource(pubReq));

      // test RejectedExecutionException by shutting down the thread pool
      PublicLocalizer publicLocalizer = spyService.getPublicLocalizer();
      publicLocalizer.threadPool.shutdown();

      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      dispatcher.await();
      tracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC,
            user, appId);
      Assert.assertNull(tracker.getLocalizedResource(pubReq));

    } finally {
      // if we call stop with events in the queue, an InterruptedException gets
      // thrown resulting in the dispatcher thread causing a system exit
      dispatcher.await();
      dispatcher.stop();
    }
  }

  @Test(timeout = 100000)
  @SuppressWarnings("unchecked")
  public void testParallelDownloadAttemptsForPrivateResource() throws Exception {

    DrainDispatcher dispatcher1 = null;
    try {
      dispatcher1 = new DrainDispatcher();
      String user = "testuser";
      ApplicationId appId = BuilderUtils.newApplicationId(1, 1);

      // creating one local directory
      List<Path> localDirs = new ArrayList<Path>();
      String[] sDirs = new String[1];
      for (int i = 0; i < 1; ++i) {
        localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
        sDirs[i] = localDirs.get(i).toString();
      }
      conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

      LocalDirsHandlerService localDirHandler = new LocalDirsHandlerService();
      localDirHandler.init(conf);
      // Registering event handlers
      EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
      dispatcher1.register(ApplicationEventType.class, applicationBus);
      EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
      dispatcher1.register(ContainerEventType.class, containerBus);

      ContainerExecutor exec = mock(ContainerExecutor.class);
      DeletionService delService = mock(DeletionService.class);
      LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
      // initializing directory handler.
      dirsHandler.init(conf);

      dispatcher1.init(conf);
      dispatcher1.start();

      ResourceLocalizationService rls =
          new ResourceLocalizationService(dispatcher1, exec, delService,
            localDirHandler, nmContext);
      dispatcher1.register(LocalizationEventType.class, rls);
      rls.init(conf);

      rls.handle(createApplicationLocalizationEvent(user, appId));

      LocalResourceRequest req =
          new LocalResourceRequest(new Path("file:///tmp"), 123L,
            LocalResourceType.FILE, LocalResourceVisibility.PRIVATE, "");

      // We need to pre-populate the LocalizerRunner as the
      // Resource Localization Service code internally starts them which
      // definitely we don't want.

      // creating new containers and populating corresponding localizer runners

      // Container - 1
      ContainerImpl container1 = createMockContainer(user, 1);
      String localizerId1 = container1.getContainerId().toString();
      rls.getPrivateLocalizers().put(
        localizerId1,
        rls.new LocalizerRunner(new LocalizerContext(user, container1
          .getContainerId(), null), localizerId1));
      LocalizerRunner localizerRunner1 = rls.getLocalizerRunner(localizerId1);

      dispatcher1.getEventHandler().handle(
        createContainerLocalizationEvent(container1,
          LocalResourceVisibility.PRIVATE, req));
      Assert
        .assertTrue(waitForPrivateDownloadToStart(rls, localizerId1, 1, 200));

      // Container - 2 now makes the request.
      ContainerImpl container2 = createMockContainer(user, 2);
      String localizerId2 = container2.getContainerId().toString();
      rls.getPrivateLocalizers().put(
        localizerId2,
        rls.new LocalizerRunner(new LocalizerContext(user, container2
          .getContainerId(), null), localizerId2));
      LocalizerRunner localizerRunner2 = rls.getLocalizerRunner(localizerId2);
      dispatcher1.getEventHandler().handle(
        createContainerLocalizationEvent(container2,
          LocalResourceVisibility.PRIVATE, req));
      Assert
        .assertTrue(waitForPrivateDownloadToStart(rls, localizerId2, 1, 200));

      // Retrieving localized resource.
      LocalResourcesTracker tracker =
          rls.getLocalResourcesTracker(LocalResourceVisibility.PRIVATE, user,
            appId);
      LocalizedResource lr = tracker.getLocalizedResource(req);
      // Resource would now have moved into DOWNLOADING state
      Assert.assertEquals(ResourceState.DOWNLOADING, lr.getState());
      // Resource should have one permit
      Assert.assertEquals(1, lr.sem.availablePermits());

      // Resource Localization Service receives first heart beat from
      // ContainerLocalizer for container1
      LocalizerHeartbeatResponse response1 =
          rls.heartbeat(createLocalizerStatus(localizerId1));

      // Resource must have been added to scheduled map
      Assert.assertEquals(1, localizerRunner1.scheduled.size());
      // Checking resource in the response and also available permits for it.
      Assert.assertEquals(req.getResource(), response1.getResourceSpecs()
        .get(0).getResource().getResource());
      Assert.assertEquals(0, lr.sem.availablePermits());

      // Resource Localization Service now receives first heart beat from
      // ContainerLocalizer for container2
      LocalizerHeartbeatResponse response2 =
          rls.heartbeat(createLocalizerStatus(localizerId2));

      // Resource must not have been added to scheduled map
      Assert.assertEquals(0, localizerRunner2.scheduled.size());
      // No resource is returned in response
      Assert.assertEquals(0, response2.getResourceSpecs().size());

      // ContainerLocalizer - 1 now sends failed resource heartbeat.
      rls.heartbeat(createLocalizerStatusForFailedResource(localizerId1, req));

      // Resource Localization should fail and state is modified accordingly.
      // Also Local should be release on the LocalizedResource.
      Assert
        .assertTrue(waitForResourceState(lr, rls, req,
          LocalResourceVisibility.PRIVATE, user, appId, ResourceState.FAILED,
          200));
      Assert.assertTrue(lr.getState().equals(ResourceState.FAILED));
      Assert.assertEquals(0, localizerRunner1.scheduled.size());

      // Now Container-2 once again sends heart beat to resource localization
      // service

      // Now container-2 again try to download the resource it should still
      // not get the resource as the resource is now not in DOWNLOADING state.
      response2 = rls.heartbeat(createLocalizerStatus(localizerId2));

      // Resource must not have been added to scheduled map.
      // Also as the resource has failed download it will be removed from
      // pending list.
      Assert.assertEquals(0, localizerRunner2.scheduled.size());
      Assert.assertEquals(0, localizerRunner2.pending.size());
      Assert.assertEquals(0, response2.getResourceSpecs().size());

    } finally {
      if (dispatcher1 != null) {
        dispatcher1.stop();
      }
    }
  }
  
  

  @Test(timeout = 10000)
  @SuppressWarnings("unchecked")
  public void testLocalResourcePath() throws Exception {

    // test the local path where application and user cache files will be
    // localized.

    DrainDispatcher dispatcher1 = null;
    try {
      dispatcher1 = new DrainDispatcher();
      String user = "testuser";
      ApplicationId appId = BuilderUtils.newApplicationId(1, 1);

      // creating one local directory
      List<Path> localDirs = new ArrayList<Path>();
      String[] sDirs = new String[1];
      for (int i = 0; i < 1; ++i) {
        localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
        sDirs[i] = localDirs.get(i).toString();
      }
      conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

      LocalDirsHandlerService localDirHandler = new LocalDirsHandlerService();
      localDirHandler.init(conf);
      // Registering event handlers
      EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
      dispatcher1.register(ApplicationEventType.class, applicationBus);
      EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
      dispatcher1.register(ContainerEventType.class, containerBus);

      ContainerExecutor exec = mock(ContainerExecutor.class);
      DeletionService delService = mock(DeletionService.class);
      LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
      // initializing directory handler.
      dirsHandler.init(conf);

      dispatcher1.init(conf);
      dispatcher1.start();

      ResourceLocalizationService rls =
          new ResourceLocalizationService(dispatcher1, exec, delService,
            localDirHandler, nmContext);
      dispatcher1.register(LocalizationEventType.class, rls);
      rls.init(conf);

      rls.handle(createApplicationLocalizationEvent(user, appId));

      // We need to pre-populate the LocalizerRunner as the
      // Resource Localization Service code internally starts them which
      // definitely we don't want.

      // creating new container and populating corresponding localizer runner

      // Container - 1
      Container container1 = createMockContainer(user, 1);
      String localizerId1 = container1.getContainerId().toString();
      rls.getPrivateLocalizers().put(
        localizerId1,
        rls.new LocalizerRunner(new LocalizerContext(user, container1
          .getContainerId(), null), localizerId1));

      // Creating two requests for container
      // 1) Private resource
      // 2) Application resource
      LocalResourceRequest reqPriv =
          new LocalResourceRequest(new Path("file:///tmp1"), 123L,
            LocalResourceType.FILE, LocalResourceVisibility.PRIVATE, "");
      List<LocalResourceRequest> privList =
          new ArrayList<LocalResourceRequest>();
      privList.add(reqPriv);

      LocalResourceRequest reqApp =
          new LocalResourceRequest(new Path("file:///tmp2"), 123L,
            LocalResourceType.FILE, LocalResourceVisibility.APPLICATION, "");
      List<LocalResourceRequest> appList =
          new ArrayList<LocalResourceRequest>();
      appList.add(reqApp);

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> rsrcs =
          new HashMap<LocalResourceVisibility, Collection<LocalResourceRequest>>();
      rsrcs.put(LocalResourceVisibility.APPLICATION, appList);
      rsrcs.put(LocalResourceVisibility.PRIVATE, privList);

      dispatcher1.getEventHandler().handle(
        new ContainerLocalizationRequestEvent(container1, rsrcs));

      // Now waiting for resource download to start. Here actual will not start
      // Only the resources will be populated into pending list.
      Assert
        .assertTrue(waitForPrivateDownloadToStart(rls, localizerId1, 2, 500));

      // Validating user and application cache paths

      String userCachePath =
          StringUtils.join(Path.SEPARATOR, Arrays.asList(localDirs.get(0)
            .toUri().getRawPath(), ContainerLocalizer.USERCACHE, user,
            ContainerLocalizer.FILECACHE));
      String userAppCachePath =
          StringUtils.join(Path.SEPARATOR, Arrays.asList(localDirs.get(0)
            .toUri().getRawPath(), ContainerLocalizer.USERCACHE, user,
            ContainerLocalizer.APPCACHE, appId.toString(),
            ContainerLocalizer.FILECACHE));

      // Now the Application and private resources may come in any order
      // for download.
      // For User cahce :
      // returned destinationPath = user cache path + random number
      // For App cache :
      // returned destinationPath = user app cache path + random number

      int returnedResources = 0;
      boolean appRsrc = false, privRsrc = false;
      while (returnedResources < 2) {
        LocalizerHeartbeatResponse response =
            rls.heartbeat(createLocalizerStatus(localizerId1));
        for (ResourceLocalizationSpec resourceSpec : response
          .getResourceSpecs()) {
          returnedResources++;
          Path destinationDirectory =
              new Path(resourceSpec.getDestinationDirectory().getFile());
          if (resourceSpec.getResource().getVisibility() ==
              LocalResourceVisibility.APPLICATION) {
            appRsrc = true;
            Assert.assertEquals(userAppCachePath, destinationDirectory
              .getParent().toUri().toString());
          } else if (resourceSpec.getResource().getVisibility() == 
              LocalResourceVisibility.PRIVATE) {
            privRsrc = true;
            Assert.assertEquals(userCachePath, destinationDirectory.getParent()
              .toUri().toString());
          } else {
            throw new Exception("Unexpected resource recevied.");
          }
        }
      }
      // We should receive both the resources (Application and Private)
      Assert.assertTrue(appRsrc && privRsrc);
    } finally {
      if (dispatcher1 != null) {
        dispatcher1.stop();
      }
    }
  }

  private LocalizerStatus createLocalizerStatusForFailedResource(
      String localizerId, LocalResourceRequest req) {
    LocalizerStatus status = createLocalizerStatus(localizerId);
    LocalResourceStatus resourceStatus = new LocalResourceStatusPBImpl();
    resourceStatus.setException(SerializedException
      .newInstance(new YarnException("test")));
    resourceStatus.setStatus(ResourceStatusType.FETCH_FAILURE);
    resourceStatus.setResource(req);
    status.addResourceStatus(resourceStatus);
    return status;
  }

  private LocalizerStatus createLocalizerStatus(String localizerId1) {
    LocalizerStatus status = new LocalizerStatusPBImpl();
    status.setLocalizerId(localizerId1);
    return status;
  }

  private LocalizationEvent createApplicationLocalizationEvent(String user,
      ApplicationId appId) {
    Application app = mock(Application.class);
    when(app.getUser()).thenReturn(user);
    when(app.getAppId()).thenReturn(appId);
    return new ApplicationLocalizationEvent(
      LocalizationEventType.INIT_APPLICATION_RESOURCES, app);
  }

  @Test(timeout = 100000)
  @SuppressWarnings("unchecked")
  public void testParallelDownloadAttemptsForPublicResource() throws Exception {

    DrainDispatcher dispatcher1 = null;
    String user = "testuser";
    try {
      // creating one local directory
      List<Path> localDirs = new ArrayList<Path>();
      String[] sDirs = new String[1];
      for (int i = 0; i < 1; ++i) {
        localDirs.add(lfs.makeQualified(new Path(basedir, i + "")));
        sDirs[i] = localDirs.get(i).toString();
      }
      conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);

      // Registering event handlers
      EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
      dispatcher1 = new DrainDispatcher();
      dispatcher1.register(ApplicationEventType.class, applicationBus);
      EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
      dispatcher1.register(ContainerEventType.class, containerBus);

      ContainerExecutor exec = mock(ContainerExecutor.class);
      DeletionService delService = mock(DeletionService.class);
      LocalDirsHandlerService dirsHandler = new LocalDirsHandlerService();
      // initializing directory handler.
      dirsHandler.init(conf);

      dispatcher1.init(conf);
      dispatcher1.start();

      // Creating and initializing ResourceLocalizationService but not starting
      // it as otherwise it will remove requests from pending queue.
      ResourceLocalizationService rawService =
          new ResourceLocalizationService(dispatcher1, exec, delService,
            dirsHandler, nmContext);
      ResourceLocalizationService spyService = spy(rawService);
      dispatcher1.register(LocalizationEventType.class, spyService);
      spyService.init(conf);

      // Initially pending map should be empty for public localizer
      Assert.assertEquals(0, spyService.getPublicLocalizer().pending.size());

      LocalResourceRequest req =
          new LocalResourceRequest(new Path("/tmp"), 123L,
            LocalResourceType.FILE, LocalResourceVisibility.PUBLIC, "");

      // Initializing application
      ApplicationImpl app = mock(ApplicationImpl.class);
      ApplicationId appId = BuilderUtils.newApplicationId(1, 1);
      when(app.getAppId()).thenReturn(appId);
      when(app.getUser()).thenReturn(user);
      dispatcher1.getEventHandler().handle(
        new ApplicationLocalizationEvent(
          LocalizationEventType.INIT_APPLICATION_RESOURCES, app));

      // Container - 1

      // container requesting the resource
      ContainerImpl container1 = createMockContainer(user, 1);
      dispatcher1.getEventHandler().handle(
        createContainerLocalizationEvent(container1,
          LocalResourceVisibility.PUBLIC, req));

      // Waiting for resource to change into DOWNLOADING state.
      Assert.assertTrue(waitForResourceState(null, spyService, req,
        LocalResourceVisibility.PUBLIC, user, null, ResourceState.DOWNLOADING,
        200));

      // Waiting for download to start.
      Assert.assertTrue(waitForPublicDownloadToStart(spyService, 1, 200));

      LocalizedResource lr =
          getLocalizedResource(spyService, req, LocalResourceVisibility.PUBLIC,
            user, null);
      // Resource would now have moved into DOWNLOADING state
      Assert.assertEquals(ResourceState.DOWNLOADING, lr.getState());

      // pending should have this resource now.
      Assert.assertEquals(1, spyService.getPublicLocalizer().pending.size());
      // Now resource should have 0 permit.
      Assert.assertEquals(0, lr.sem.availablePermits());

      // Container - 2

      // Container requesting the same resource.
      ContainerImpl container2 = createMockContainer(user, 2);
      dispatcher1.getEventHandler().handle(
        createContainerLocalizationEvent(container2,
          LocalResourceVisibility.PUBLIC, req));

      // Waiting for download to start. This should return false as new download
      // will not start
      Assert.assertFalse(waitForPublicDownloadToStart(spyService, 2, 100));

      // Now Failing the resource download. As a part of it
      // resource state is changed and then lock is released.
      ResourceFailedLocalizationEvent locFailedEvent =
          new ResourceFailedLocalizationEvent(
              req,new Exception("test").toString());
      spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC, user,
        null).handle(locFailedEvent);

      // Waiting for resource to change into FAILED state.
      Assert.assertTrue(waitForResourceState(lr, spyService, req,
        LocalResourceVisibility.PUBLIC, user, null, ResourceState.FAILED, 200));
      // releasing lock as a part of download failed process.
      lr.unlock();
      // removing pending download request.
      spyService.getPublicLocalizer().pending.clear();

      // Now I need to simulate a race condition wherein Event is added to
      // dispatcher before resource state changes to either FAILED or LOCALIZED
      // Hence sending event directly to dispatcher.
      LocalizerResourceRequestEvent localizerEvent =
          new LocalizerResourceRequestEvent(lr, null,
            mock(LocalizerContext.class), null);

      dispatcher1.getEventHandler().handle(localizerEvent);
      // Waiting for download to start. This should return false as new download
      // will not start
      Assert.assertFalse(waitForPublicDownloadToStart(spyService, 1, 100));
      // Checking available permits now.
      Assert.assertEquals(1, lr.sem.availablePermits());

    } finally {
      if (dispatcher1 != null) {
        dispatcher1.stop();
      }
    }

  }

  private boolean waitForPrivateDownloadToStart(
      ResourceLocalizationService service, String localizerId, int size,
      int maxWaitTime) {
    List<LocalizerResourceRequestEvent> pending = null;
    // Waiting for localizer to be created.
    do {
      if (service.getPrivateLocalizers().get(localizerId) != null) {
        pending = service.getPrivateLocalizers().get(localizerId).pending;
      }
      if (pending == null) {
        try {
          maxWaitTime -= 20;
          Thread.sleep(20);
        } catch (Exception e) {
        }
      } else {
        break;
      }
    } while (maxWaitTime > 0);
    if (pending == null) {
      return false;
    }
    do {
      if (pending.size() == size) {
        return true;
      } else {
        try {
          maxWaitTime -= 20;
          Thread.sleep(20);
        } catch (Exception e) {
        }
      }
    } while (maxWaitTime > 0);
    return pending.size() == size;
  }

  private boolean waitForPublicDownloadToStart(
      ResourceLocalizationService service, int size, int maxWaitTime) {
    Map<Future<Path>, LocalizerResourceRequestEvent> pending = null;
    // Waiting for localizer to be created.
    do {
      if (service.getPublicLocalizer() != null) {
        pending = service.getPublicLocalizer().pending;
      }
      if (pending == null) {
        try {
          maxWaitTime -= 20;
          Thread.sleep(20);
        } catch (Exception e) {
        }
      } else {
        break;
      }
    } while (maxWaitTime > 0);
    if (pending == null) {
      return false;
    }
    do {
      if (pending.size() == size) {
        return true;
      } else {
        try {
          maxWaitTime -= 20;
          Thread.sleep(20);
        } catch (InterruptedException e) {
        }
      }
    } while (maxWaitTime > 0);
    return pending.size() == size;

  }

  private LocalizedResource getLocalizedResource(
      ResourceLocalizationService service, LocalResourceRequest req,
      LocalResourceVisibility vis, String user, ApplicationId appId) {
    return service.getLocalResourcesTracker(vis, user, appId)
      .getLocalizedResource(req);
  }

  private boolean waitForResourceState(LocalizedResource lr,
      ResourceLocalizationService service, LocalResourceRequest req,
      LocalResourceVisibility vis, String user, ApplicationId appId,
      ResourceState resourceState, long maxWaitTime) {
    LocalResourcesTracker tracker = null;
    // checking tracker is created
    do {
      if (tracker == null) {
        tracker = service.getLocalResourcesTracker(vis, user, appId);
      }
      if (tracker != null && lr == null) {
        lr = tracker.getLocalizedResource(req);
      }
      if (lr != null) {
        break;
      } else {
        try {
          maxWaitTime -= 20;
          Thread.sleep(20);
        } catch (InterruptedException e) {
        }
      }
    } while (maxWaitTime > 0);
    // this will wait till resource state is changed to (resourceState).
    if (lr == null) {
      return false;
    }
    do {
      if (!lr.getState().equals(resourceState)) {
        try {
          maxWaitTime -= 50;
          Thread.sleep(50);
        } catch (InterruptedException e) {
        }
      } else {
        break;
      }
    } while (maxWaitTime > 0);
    return lr.getState().equals(resourceState);
  }

  private ContainerLocalizationRequestEvent createContainerLocalizationEvent(
      ContainerImpl container, LocalResourceVisibility vis,
      LocalResourceRequest req) {
    Map<LocalResourceVisibility, Collection<LocalResourceRequest>> reqs =
        new HashMap<LocalResourceVisibility, Collection<LocalResourceRequest>>();
    List<LocalResourceRequest> resourceList =
        new ArrayList<LocalResourceRequest>();
    resourceList.add(req);
    reqs.put(vis, resourceList);
    return new ContainerLocalizationRequestEvent(container, reqs);
  }

  private ContainerImpl createMockContainer(String user, int containerId) {
    ContainerImpl container = mock(ContainerImpl.class);
    when(container.getContainerId()).thenReturn(
      BuilderUtils.newContainerId(1, 1, 1, containerId));
    when(container.getUser()).thenReturn(user);
    Credentials mockCredentials = mock(Credentials.class);
    when(container.getCredentials()).thenReturn(mockCredentials);
    return container;
  }

  private static URL getPath(String path) {
    URL url = BuilderUtils.newURL("file", null, 0, path);
    return url;
  }

  private static LocalResource getMockedResource(Random r, 
      LocalResourceVisibility vis) {
    String name = Long.toHexString(r.nextLong());
    URL url = getPath("/local/PRIVATE/" + name);
    LocalResource rsrc =
        BuilderUtils.newLocalResource(url, LocalResourceType.FILE, vis,
            r.nextInt(1024) + 1024L, r.nextInt(1024) + 2048L, false);
    return rsrc;
  }
  
  private static LocalResource getAppMockedResource(Random r) {
    return getMockedResource(r, LocalResourceVisibility.APPLICATION);
  }
  
  private static LocalResource getPublicMockedResource(Random r) {
    return getMockedResource(r, LocalResourceVisibility.PUBLIC);
  }
  
  private static LocalResource getPrivateMockedResource(Random r) {
    return getMockedResource(r, LocalResourceVisibility.PRIVATE);
  }

  private static Container getMockContainer(ApplicationId appId, int id,
      String user) {
    Container c = mock(Container.class);
    ApplicationAttemptId appAttemptId =
        BuilderUtils.newApplicationAttemptId(appId, 1);
    ContainerId cId = BuilderUtils.newContainerId(appAttemptId, id);
    when(c.getUser()).thenReturn(user);
    when(c.getContainerId()).thenReturn(cId);
    Credentials creds = new Credentials();
    creds.addToken(new Text("tok" + id), getToken(id));
    when(c.getCredentials()).thenReturn(creds);
    when(c.toString()).thenReturn(cId.toString());
    return c;
  }

  private ResourceLocalizationService createSpyService(
      DrainDispatcher dispatcher, LocalDirsHandlerService dirsHandler,
      NMStateStoreService stateStore) {
    ContainerExecutor exec = mock(ContainerExecutor.class);
    LocalizerTracker mockLocalizerTracker = mock(LocalizerTracker.class);
    DeletionService delService = mock(DeletionService.class);
    NMContext nmContext =
        new NMContext(new NMContainerTokenSecretManager(conf),
          new NMTokenSecretManagerInNM(), null,
          new ApplicationACLsManager(conf), stateStore);
    ResourceLocalizationService rawService =
      new ResourceLocalizationService(dispatcher, exec, delService,
                                      dirsHandler, nmContext);
    ResourceLocalizationService spyService = spy(rawService);
    doReturn(mockServer).when(spyService).createServer();
    doReturn(mockLocalizerTracker).when(spyService).createLocalizerTracker(
        isA(Configuration.class));
    doReturn(lfs).when(spyService)
        .getLocalFileContext(isA(Configuration.class));
    return spyService;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  static Token<? extends TokenIdentifier> getToken(int id) {
    return new Token(("ident" + id).getBytes(), ("passwd" + id).getBytes(),
        new Text("kind" + id), new Text("service" + id));
  }
  
  /*
   * Test to ensure ResourceLocalizationService can handle local dirs going bad.
   * Test first sets up all the components required, then sends events to fetch
   * a private, app and public resource. It then sends events to clean up the
   * container and the app and ensures the right delete calls were made.
   */
  @Test
  @SuppressWarnings("unchecked")
  // mocked generics
  public void testFailedDirsResourceRelease() throws Exception {
    // setup components
    File f = new File(basedir.toString());
    String[] sDirs = new String[4];
    List<Path> localDirs = new ArrayList<Path>(sDirs.length);
    for (int i = 0; i < 4; ++i) {
      sDirs[i] = f.getAbsolutePath() + i;
      localDirs.add(new Path(sDirs[i]));
    }
    List<Path> containerLocalDirs = new ArrayList<Path>(localDirs.size());
    List<Path> appLocalDirs = new ArrayList<Path>(localDirs.size());
    List<Path> nmLocalContainerDirs = new ArrayList<Path>(localDirs.size());
    List<Path> nmLocalAppDirs = new ArrayList<Path>(localDirs.size());
    conf.setStrings(YarnConfiguration.NM_LOCAL_DIRS, sDirs);
    conf.setLong(YarnConfiguration.NM_DISK_HEALTH_CHECK_INTERVAL_MS, 500);

    LocalizerTracker mockLocallilzerTracker = mock(LocalizerTracker.class);
    DrainDispatcher dispatcher = new DrainDispatcher();
    dispatcher.init(conf);
    dispatcher.start();
    EventHandler<ApplicationEvent> applicationBus = mock(EventHandler.class);
    dispatcher.register(ApplicationEventType.class, applicationBus);
    EventHandler<ContainerEvent> containerBus = mock(EventHandler.class);
    dispatcher.register(ContainerEventType.class, containerBus);
    // Ignore actual localization
    EventHandler<LocalizerEvent> localizerBus = mock(EventHandler.class);
    dispatcher.register(LocalizerEventType.class, localizerBus);

    ContainerExecutor exec = mock(ContainerExecutor.class);
    LocalDirsHandlerService mockDirsHandler =
        mock(LocalDirsHandlerService.class);
    doReturn(new ArrayList<String>(Arrays.asList(sDirs))).when(
        mockDirsHandler).getLocalDirsForCleanup();

    DeletionService delService = mock(DeletionService.class);

    // setup mocks
    ResourceLocalizationService rawService =
        new ResourceLocalizationService(dispatcher, exec, delService,
          mockDirsHandler, nmContext);
    ResourceLocalizationService spyService = spy(rawService);
    doReturn(mockServer).when(spyService).createServer();
    doReturn(mockLocallilzerTracker).when(spyService).createLocalizerTracker(
      isA(Configuration.class));
    doReturn(lfs).when(spyService)
      .getLocalFileContext(isA(Configuration.class));
    FsPermission defaultPermission =
        FsPermission.getDirDefault().applyUMask(lfs.getUMask());
    FsPermission nmPermission =
        ResourceLocalizationService.NM_PRIVATE_PERM.applyUMask(lfs.getUMask());
    final FileStatus fs =
        new FileStatus(0, true, 1, 0, System.currentTimeMillis(), 0,
          defaultPermission, "", "", localDirs.get(0));
    final FileStatus nmFs =
        new FileStatus(0, true, 1, 0, System.currentTimeMillis(), 0,
          nmPermission, "", "", localDirs.get(0));

    final String user = "user0";
    // init application
    final Application app = mock(Application.class);
    final ApplicationId appId =
        BuilderUtils.newApplicationId(314159265358979L, 3);
    when(app.getUser()).thenReturn(user);
    when(app.getAppId()).thenReturn(appId);
    when(app.toString()).thenReturn(ConverterUtils.toString(appId));

    // init container.
    final Container c = getMockContainer(appId, 42, user);

    // setup local app dirs
    List<String> tmpDirs = mockDirsHandler.getLocalDirs();
    for (int i = 0; i < tmpDirs.size(); ++i) {
      Path usersdir = new Path(tmpDirs.get(i), ContainerLocalizer.USERCACHE);
      Path userdir = new Path(usersdir, user);
      Path allAppsdir = new Path(userdir, ContainerLocalizer.APPCACHE);
      Path appDir = new Path(allAppsdir, ConverterUtils.toString(appId));
      Path containerDir =
          new Path(appDir, ConverterUtils.toString(c.getContainerId()));
      containerLocalDirs.add(containerDir);
      appLocalDirs.add(appDir);

      Path sysDir =
          new Path(tmpDirs.get(i), ResourceLocalizationService.NM_PRIVATE_DIR);
      Path appSysDir = new Path(sysDir, ConverterUtils.toString(appId));
      Path containerSysDir =
          new Path(appSysDir, ConverterUtils.toString(c.getContainerId()));

      nmLocalContainerDirs.add(containerSysDir);
      nmLocalAppDirs.add(appSysDir);
    }

    try {
      spyService.init(conf);
      spyService.start();

      spyService.handle(new ApplicationLocalizationEvent(
        LocalizationEventType.INIT_APPLICATION_RESOURCES, app));
      dispatcher.await();

      // Get a handle on the trackers after they're setup with
      // INIT_APP_RESOURCES
      LocalResourcesTracker appTracker =
          spyService.getLocalResourcesTracker(
            LocalResourceVisibility.APPLICATION, user, appId);
      LocalResourcesTracker privTracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PRIVATE,
            user, appId);
      LocalResourcesTracker pubTracker =
          spyService.getLocalResourcesTracker(LocalResourceVisibility.PUBLIC,
            user, appId);

      // init resources
      Random r = new Random();
      long seed = r.nextLong();
      r.setSeed(seed);

      // Send localization requests, one for each type of resource
      final LocalResource privResource = getPrivateMockedResource(r);
      final LocalResourceRequest privReq =
          new LocalResourceRequest(privResource);

      final LocalResource appResource = getAppMockedResource(r);
      final LocalResourceRequest appReq = new LocalResourceRequest(appResource);

      final LocalResource pubResource = getPublicMockedResource(r);
      final LocalResourceRequest pubReq = new LocalResourceRequest(pubResource);

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req =
          new HashMap<LocalResourceVisibility, Collection<LocalResourceRequest>>();
      req.put(LocalResourceVisibility.PRIVATE,
        Collections.singletonList(privReq));
      req.put(LocalResourceVisibility.APPLICATION,
        Collections.singletonList(appReq));
      req
        .put(LocalResourceVisibility.PUBLIC, Collections.singletonList(pubReq));

      Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req2 =
          new HashMap<LocalResourceVisibility, Collection<LocalResourceRequest>>();
      req2.put(LocalResourceVisibility.PRIVATE,
        Collections.singletonList(privReq));

      // Send Request event
      spyService.handle(new ContainerLocalizationRequestEvent(c, req));
      spyService.handle(new ContainerLocalizationRequestEvent(c, req2));
      dispatcher.await();

      int privRsrcCount = 0;
      for (LocalizedResource lr : privTracker) {
        privRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 2, lr.getRefCount());
        Assert.assertEquals(privReq, lr.getRequest());
      }
      Assert.assertEquals(1, privRsrcCount);

      int appRsrcCount = 0;
      for (LocalizedResource lr : appTracker) {
        appRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 1, lr.getRefCount());
        Assert.assertEquals(appReq, lr.getRequest());
      }
      Assert.assertEquals(1, appRsrcCount);

      int pubRsrcCount = 0;
      for (LocalizedResource lr : pubTracker) {
        pubRsrcCount++;
        Assert.assertEquals("Incorrect reference count", 1, lr.getRefCount());
        Assert.assertEquals(pubReq, lr.getRequest());
      }
      Assert.assertEquals(1, pubRsrcCount);

      // setup mocks for test, a set of dirs with IOExceptions and let the rest
      // go through
      for (int i = 0; i < containerLocalDirs.size(); ++i) {
        if (i == 2) {
          Mockito.doThrow(new IOException()).when(spylfs)
            .getFileStatus(eq(containerLocalDirs.get(i)));
          Mockito.doThrow(new IOException()).when(spylfs)
            .getFileStatus(eq(nmLocalContainerDirs.get(i)));
        } else {
          doReturn(fs).when(spylfs)
            .getFileStatus(eq(containerLocalDirs.get(i)));
          doReturn(nmFs).when(spylfs).getFileStatus(
            eq(nmLocalContainerDirs.get(i)));
        }
      }

      // Send Cleanup Event
      spyService.handle(new ContainerLocalizationCleanupEvent(c, req));
      verify(mockLocallilzerTracker).cleanupPrivLocalizers(
        "container_314159265358979_0003_01_000042");

      // match cleanup events with the mocks we setup earlier
      for (int i = 0; i < containerLocalDirs.size(); ++i) {
        if (i == 2) {
          try {
            verify(delService).delete(user, containerLocalDirs.get(i));
            verify(delService).delete(null, nmLocalContainerDirs.get(i));
            Assert.fail("deletion attempts for invalid dirs");
          } catch (Throwable e) {
            continue;
          }
        } else {
          verify(delService).delete(user, containerLocalDirs.get(i));
          verify(delService).delete(null, nmLocalContainerDirs.get(i));
        }
      }

      ArgumentMatcher<ApplicationEvent> matchesAppDestroy =
          new ArgumentMatcher<ApplicationEvent>() {
            @Override
            public boolean matches(Object o) {
              ApplicationEvent evt = (ApplicationEvent) o;
              return (evt.getType() == ApplicationEventType.APPLICATION_RESOURCES_CLEANEDUP)
                  && appId == evt.getApplicationID();
            }
          };

      dispatcher.await();

      // setup mocks again, this time throw UnsupportedFileSystemException and
      // IOExceptions
      for (int i = 0; i < containerLocalDirs.size(); ++i) {
        if (i == 3) {
          Mockito.doThrow(new IOException()).when(spylfs)
            .getFileStatus(eq(appLocalDirs.get(i)));
          Mockito.doThrow(new UnsupportedFileSystemException("test"))
            .when(spylfs).getFileStatus(eq(nmLocalAppDirs.get(i)));
        } else {
          doReturn(fs).when(spylfs).getFileStatus(eq(appLocalDirs.get(i)));
          doReturn(nmFs).when(spylfs).getFileStatus(eq(nmLocalAppDirs.get(i)));
        }
      }
      LocalizationEvent destroyApp =
          new ApplicationLocalizationEvent(
            LocalizationEventType.DESTROY_APPLICATION_RESOURCES, app);
      spyService.handle(destroyApp);
      verify(applicationBus).handle(argThat(matchesAppDestroy));

      // verify we got the right delete calls
      for (int i = 0; i < containerLocalDirs.size(); ++i) {
        if (i == 3) {
          try {
            verify(delService).delete(user, containerLocalDirs.get(i));
            verify(delService).delete(null, nmLocalContainerDirs.get(i));
            Assert.fail("deletion attempts for invalid dirs");
          } catch (Throwable e) {
            continue;
          }
        } else {
          verify(delService).delete(user, appLocalDirs.get(i));
          verify(delService).delete(null, nmLocalAppDirs.get(i));
        }
      }

    } finally {
      dispatcher.stop();
      delService.stop();
    }
  }

}
