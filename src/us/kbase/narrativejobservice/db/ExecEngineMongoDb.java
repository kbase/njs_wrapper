package us.kbase.narrativejobservice.db;

import java.io.IOException;
import java.net.UnknownHostException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.logging.Level;

import org.jongo.Jongo;
import org.jongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.kbase.common.mongo.GetMongoDB;
import us.kbase.common.mongo.exceptions.InvalidHostException;
import us.kbase.common.mongo.exceptions.MongoAuthException;

import com.google.common.collect.Lists;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;

public class ExecEngineMongoDb {
	private Jongo jongo;
	private DBCollection taskCol;
	private DBCollection logCol;
	private DBCollection propCol;
	private MongoCollection execTasks;
	private MongoCollection execLogs;
	private MongoCollection srvProps;

	private static final Map<String, MongoClient> HOSTS_TO_CLIENT = new HashMap<>();

	private static final String COL_EXEC_TASKS = "exec_tasks";
	private static final String PK_EXEC_TASKS = "ujs_job_id";
	private static final String COL_EXEC_LOGS = "exec_logs";
	private static final String PK_EXEC_LOGS = "ujs_job_id";
	private static final String COL_SRV_PROPS = "srv_props";
	private static final String PK_SRV_PROPS = "prop_id";
	private static final String SRV_PROPS_VALUE = "value";
	private static final String SRV_PROP_DB_VERSION = "db_version";

	private static final String DB_VERSION = "1.0";

	// should really inject the DB, but worry about that later.
	public ExecEngineMongoDb(
			final String hosts,
			final String db,
			final String user,
			final String pwd)
			throws Exception {
		final DB mongo = getDB(hosts, db, user, pwd, 0, 10);
		taskCol = mongo.getCollection(COL_EXEC_TASKS);
		logCol = mongo.getCollection(COL_EXEC_LOGS);
		propCol = mongo.getCollection(COL_SRV_PROPS);
		jongo = new Jongo(mongo);
		execTasks = jongo.getCollection(COL_EXEC_TASKS);
		execLogs = jongo.getCollection(COL_EXEC_LOGS);
		srvProps = jongo.getCollection(COL_SRV_PROPS);
		// Indexing
		final BasicDBObject unique = new BasicDBObject("unique", true);
		taskCol.createIndex(new BasicDBObject(PK_EXEC_TASKS, 1), unique);
		logCol.createIndex(new BasicDBObject(PK_EXEC_LOGS, 1), unique);
		propCol.createIndex(new BasicDBObject(PK_SRV_PROPS, 1), unique);

		try {
			// at some point check that the db ver = sw ver
			propCol.insert(new BasicDBObject(PK_SRV_PROPS, SRV_PROP_DB_VERSION)
					.append(SRV_PROPS_VALUE, DB_VERSION));
		} catch (DuplicateKeyException e) {
			//version is already there so do nothing
		}
	}

	public String[] getSubJobIds(String ujsJobId) throws Exception{
		// there should be a null/empty check for the ujs id here
		final DBCursor dbc = taskCol.find(
				new BasicDBObject("parent_job_id", ujsJobId),
				new BasicDBObject("ujs_job_id", 1));
		List<String> idList = new ArrayList<String>();
		for (final DBObject dbo: dbc) {
			idList.add((String) dbo.get("ujs_job_id"));
		}
		return idList.toArray(new String[idList.size()]);
	}


	public ExecLog getExecLog(String ujsJobId) throws Exception {
		List<ExecLog> ret = Lists.newArrayList(execLogs.find(
				String.format("{%s:#}", PK_EXEC_LOGS), ujsJobId)
				.projection(String.format("{%s:1,%s:1,%s:1}", PK_EXEC_LOGS,
						"original_line_count", "stored_line_count")).as(ExecLog.class));
		return ret.size() > 0 ? ret.get(0) : null;
	}

	public void insertExecLog(ExecLog execLog) throws Exception {
		execLogs.insert(execLog);
	}

	public void insertExecLogs(List<ExecLog> execLogList) throws Exception {
		Object[] execLogArray = execLogList.toArray(new Object[execLogList.size()]);
		execLogs.insert(execLogArray);
	}

	public void updateExecLogLines(String ujsJobId, int newLineCount,
								   List<ExecLogLine> newLines) throws Exception {
		execLogs.update(String.format("{%s:#}", PK_EXEC_LOGS), ujsJobId).with(
				String.format("{$set:{%s:#,%s:#},$push:{%s:{$each:#}}}",
						"original_line_count", "stored_line_count", "lines"),
				newLineCount, newLineCount, newLines);
	}

	public void updateExecLogOriginalLineCount(String ujsJobId, int newLineCount) throws Exception {
		execLogs.update(String.format("{%s:#}", PK_EXEC_LOGS), ujsJobId).with(
				String.format("{$set:{%s:#}}", "original_line_count"), newLineCount);
	}

	public List<ExecLogLine> getExecLogLines(String ujsJobId, int from, int count) throws Exception {
		return execLogs.findOne(String.format("{%s:#}", PK_EXEC_LOGS), ujsJobId).projection(
				String.format("{%s:{$slice:[#,#]}}", "lines"), from, count).as(ExecLog.class).getLines();
	}

	public void insertExecTask(ExecTask execTask) throws Exception {
		execTasks.insert(execTask);
	}

	public void addExecTaskResult(
			final String ujsJobId,
			final Map<String, Object> result) {
		execTasks.update(String.format("{%s: #}", PK_EXEC_TASKS), ujsJobId)
				.with(String.format("{$set: {%s: #}}", "job_output"), result);
	}

	public ExecTask getExecTask(String ujsJobId) throws Exception {
		List<ExecTask> ret = Lists.newArrayList(execTasks.find(
				String.format("{%s:#}", PK_EXEC_TASKS), ujsJobId).as(ExecTask.class));
		return ret.size() > 0 ? ret.get(0) : null;
	}

	public void updateExecTaskTime(String ujsJobId, boolean finishTime, long time) throws Exception {
		execTasks.update(String.format("{%s:#}", PK_EXEC_TASKS), ujsJobId).with(
				String.format("{$set:{%s:#}}", finishTime ? "finish_time" : "exec_start_time"), time);
	}

	public String getServiceProperty(String propId) throws Exception {
		@SuppressWarnings("rawtypes")
		List<Map> ret = Lists.newArrayList(srvProps.find(String.format("{%s:#}", PK_SRV_PROPS), propId)
				.projection(String.format("{%s:1}", SRV_PROPS_VALUE)).as(Map.class));
		if (ret.size() == 0)
			return null;
		return (String)ret.get(0).get(SRV_PROPS_VALUE);
	}

	public void setServiceProperty(String propId, String value) throws Exception {
		srvProps.update(String.format("{%s:#}", PK_SRV_PROPS), propId).upsert().with(
				String.format("{$set:{%s:#}}", SRV_PROPS_VALUE), value);
	}

	private synchronized static MongoClient getMongoClient(final String hosts)
			throws UnknownHostException, InvalidHostException {
		//Only make one instance of MongoClient per JVM per mongo docs
		final MongoClient client;
		if (!HOSTS_TO_CLIENT.containsKey(hosts)) {
			// Don't print to stderr
			java.util.logging.Logger.getLogger("com.mongodb")
					.setLevel(Level.OFF);
			@SuppressWarnings("deprecation")
			final MongoClientOptions opts = MongoClientOptions.builder()
					.autoConnectRetry(true).build();
			try {
				List<ServerAddress> addr = new ArrayList<ServerAddress>();
				for (String s: hosts.split(","))
					addr.add(new ServerAddress(s));
				client = new MongoClient(addr, opts);
			} catch (NumberFormatException nfe) {
				//throw a better exception if 10gen ever fixes this
				throw new InvalidHostException(hosts
						+ " is not a valid mongodb host");
			}
			HOSTS_TO_CLIENT.put(hosts, client);
		} else {
			client = HOSTS_TO_CLIENT.get(hosts);
		}
		return client;
	}

	@SuppressWarnings("deprecation")
	private static DB getDB(final String hosts, final String database,
							final String user, final String pwd,
							final int retryCount, final int logIntervalCount)
			throws UnknownHostException, InvalidHostException, IOException,
			MongoAuthException, InterruptedException {
		if (database == null || database.isEmpty()) {
			throw new IllegalArgumentException(
					"database may not be null or the empty string");
		}
		final DB db = getMongoClient(hosts).getDB(database);
		if (user != null && pwd != null) {
			int retries = 0;
			while (true) {
				try {
					db.authenticate(user, pwd.toCharArray());
					break;
				} catch (MongoException.Network men) {
					if (retries >= retryCount) {
						throw (IOException) men.getCause();
					}
					if (retries % logIntervalCount == 0) {
						getLogger().info(
								"Retrying MongoDB connection {}/{}, attempt {}/{}",
								hosts, database, retries, retryCount);
					}
					Thread.sleep(1000);
				}
				retries++;
			}
		}
		try {
			db.getCollectionNames();
		} catch (MongoException me) {
			throw new MongoAuthException("Not authorized for database "
					+ database, me);
		}
		return db;
	}

	private static Logger getLogger() {
		return LoggerFactory.getLogger(GetMongoDB.class);
	}
}