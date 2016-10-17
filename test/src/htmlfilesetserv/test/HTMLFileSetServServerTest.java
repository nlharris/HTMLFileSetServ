package htmlfilesetserv.test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import junit.framework.Assert;

import org.ini4j.Ini;
import org.ini4j.Profile.Section;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mongodb.DB;
import com.mongodb.MongoClient;

import htmlfilesetserv.HTMLFileSetHTTPServer;
import us.kbase.auth.AuthToken;
import us.kbase.auth.AuthService;
import us.kbase.common.mongo.GetMongoDB;
import us.kbase.common.service.JsonServerSyslog;
import us.kbase.common.test.TestCommon;
import us.kbase.common.test.controllers.mongo.MongoController;
import us.kbase.workspace.WorkspaceServer;
import us.kbase.workspace.test.WorkspaceTestCommon;

public class HTMLFileSetServServerTest {
	
	private static final boolean KEEP_TEMP_FILES = false;
	
	private static AuthToken token = null;
	private static Map<String, String> config = null;
	
	private static final String MONGO_PATH = "/opt/mongo/2.4.14/mongod";
	private static final String WS_DB = "ws_test";
	private static final String TYPE_DB = "type_test";
	
	private static Path SCRATCH;
	private static MongoController mongo;
	private static WorkspaceServer WS_SERVER;
	private static HTMLFileSetHTTPServer HTML_SERVER;
	
	@BeforeClass
	public static void init() throws Exception {
		TestCommon.stfuLoggers();
		//TODO TEST AUTH make configurable?
		token = AuthService.validateToken(System.getenv("KB_AUTH_TOKEN"));
		//TODO ZZEXTERNAL BLOCKER TEST need another user
		
		String configFilePath = System.getenv("KB_DEPLOYMENT_CONFIG");
		File deploy = new File(configFilePath);
		Ini ini = new Ini(deploy);
		config = ini.get("HTMLFileSetServ");
		SCRATCH = Paths.get(config.get("scratch"));
		
		// These lines are necessary because we don't want to start linux
		// syslog bridge service
		JsonServerSyslog.setStaticUseSyslog(false);
		JsonServerSyslog.setStaticMlogFile(
				new File(config.get("scratch"), "test.log").getAbsolutePath());

		mongo = new MongoController(MONGO_PATH, SCRATCH.resolve("tempmongo"));
		System.out.println("Using mongo temp dir " + mongo.getTempDir());
		
		final String mongohost = "localhost:" + mongo.getServerPort();
		MongoClient mongoClient = new MongoClient(mongohost);
		
		final DB db = mongoClient.getDB(WS_DB);
		WS_SERVER = startupWorkspaceServer(mongohost, db, TYPE_DB, token);
		mongoClient.close();
		int wsport = WS_SERVER.getServerPort();
		System.out.println("Started ws server on port " + wsport);
		
		HTML_SERVER = startupHTMLServer("http://localhost:" + wsport);
		int htmlport = HTML_SERVER.getServerPort();
		System.out.println("Started html server on port " + htmlport);
	}
	
	private static HTMLFileSetHTTPServer startupHTMLServer(
			final String wsURL)
			throws Exception {
		
		//write the server config file:
		File iniFile = File.createTempFile("test", ".cfg",
				new File(SCRATCH.toString()));
		if (iniFile.exists()) {
			iniFile.delete();
		}
		System.out.println("Created HTML serv temporary config file: " +
				iniFile.getAbsolutePath());
		Ini ini = new Ini();
		Section html = ini.add("HTMLFileSetServ");
		html.add("workspace-url", wsURL);
		html.add("scratch", SCRATCH);
		//TODO TEST make auth url configurable
		html.add("auth-service-url",
				"https://ci.kbase.us/services/authorization");
		ini.store(iniFile);
		iniFile.deleteOnExit();

		//set up env
		Map<String, String> env = TestCommon.getenv();
		env.put("KB_DEPLOYMENT_CONFIG", iniFile.getAbsolutePath());

		HTMLFileSetHTTPServer server = new HTMLFileSetHTTPServer();
		new HTMLServerThread(server).start();
		System.out.println("Main thread waiting for html server to start up");
		while (server.getServerPort() == null) {
			Thread.sleep(1000);
		}
		return server;
	}

	protected static class HTMLServerThread extends Thread {
		private HTMLFileSetHTTPServer server;
		
		protected HTMLServerThread(HTMLFileSetHTTPServer server) {
			this.server = server;
		}
		
		public void run() {
			try {
				server.startupServer();
			} catch (Exception e) {
				System.err.println("Can't start server:");
				e.printStackTrace();
			}
		}
	}
	
	
	private static WorkspaceServer startupWorkspaceServer(
			final String mongohost,
			final DB db,
			final String typedb,
			final AuthToken t)
			throws Exception {
		WorkspaceTestCommon.initializeGridFSWorkspaceDB(db, typedb);
		
		//write the server config file:
		File iniFile = File.createTempFile("test", ".cfg",
				new File(SCRATCH.toString()));
		if (iniFile.exists()) {
			iniFile.delete();
		}
		System.out.println("Created WS temporary config file: " +
				iniFile.getAbsolutePath());
		Ini ini = new Ini();
		Section ws = ini.add("Workspace");
		ws.add("mongodb-host", mongohost);
		ws.add("mongodb-database", db.getName());
		//TODO TEST make auth url configurable
		ws.add("auth-service-url",
				"https://ci.kbase.us/services/authorization");
		ws.add("auth-service-url-allow-insecure", "true");
		//TODO TEST make globus url configurable
		ws.add("globus-url", "https://nexus.api.globusonline.org/");
		ws.add("backend-secret", "foo");
		ws.add("ws-admin", t.getUserName()); //TODO TEST use alternate user
		ws.add("kbase-admin-token", t.getToken());
		ws.add("temp-dir", SCRATCH.resolve("tempworkspace"));
		ws.add("ignore-handle-service", "true");
		ini.store(iniFile);
		iniFile.deleteOnExit();
		
		//set up env
		Map<String, String> env = TestCommon.getenv();
		env.put("KB_DEPLOYMENT_CONFIG", iniFile.getAbsolutePath());

		WorkspaceServer.clearConfigForTests();
		WorkspaceServer server = new WorkspaceServer();
		new WSServerThread(server).start();
		System.out.println("Main thread waiting for server to start up");
		while (server.getServerPort() == null) {
			Thread.sleep(1000);
		}
		return server;
	}
	
	protected static class WSServerThread extends Thread {
		private WorkspaceServer server;
		
		protected WSServerThread(WorkspaceServer server) {
			this.server = server;
		}
		
		public void run() {
			try {
				server.startupServer();
			} catch (Exception e) {
				System.err.println("Can't start server:");
				e.printStackTrace();
			}
		}
	}
	
	@AfterClass
	public static void tearDownClass() throws Exception {
		if (HTML_SERVER != null) {
			System.out.print("Killing html server ... ");
			HTML_SERVER.stopServer();
			System.out.println("Done");
		}
		if (WS_SERVER != null) {
			System.out.print("Killing ws server ... ");
			WS_SERVER.stopServer();
			System.out.println("Done");
		}
		if (mongo != null) {
			System.out.println("destroying mongo temp files");
			mongo.destroy(KEEP_TEMP_FILES);
		}
	}
	@Before
	public void clearDB() throws Exception {
		DB wsdb1 = GetMongoDB.getDB("localhost:" + mongo.getServerPort(),
				WS_DB);
		TestCommon.destroyDB(wsdb1);
	}

	@Test
	public void testYourMethod() throws Exception {
		// Prepare test objects in workspace if needed using 
		// wsClient.saveObjects(new SaveObjectsParams().withWorkspace(getWsName()).withObjects(Arrays.asList(
		//         new ObjectSaveData().withType("SomeModule.SomeType").withName(objName).withData(new UObject(obj)))));
		//
		// Run your method by
		// YourRetType ret = impl.yourMethod(params, token);
		//
		// Check returned data with
		// Assert.assertEquals(..., ret.getSomeProperty());
		// ... or other JUnit methods.
	}
}
