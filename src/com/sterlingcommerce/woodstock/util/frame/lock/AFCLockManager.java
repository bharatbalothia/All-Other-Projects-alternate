package com.sterlingcommerce.woodstock.util.frame.lock;

import com.sterlingcommerce.woodstock.util.FlatUtil;
import com.sterlingcommerce.woodstock.util.frame.Manager;
import com.sterlingcommerce.woodstock.util.frame.jdbc.JDBCProps;
import com.sterlingcommerce.woodstock.util.frame.jdbc.JDBCService;
import com.sterlingcommerce.woodstock.util.frame.log.LogService;
import com.sterlingcommerce.woodstock.util.frame.log.Logger;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Vector;

public class AFCLockManager {
	protected static final String waitObj = "wait";
	protected static final String waitToken = "waitToken";
	protected static final long waitLock = 6000000L;
	public static final String ITEMNAME = "ITEMNAME";
	public static final String USERNAME = "USERNAME";
	public static final String TIMESTAMP = "TIMESTAMP";
	public static final String TIMEOUT = "TIMEOUT";
	public static final String SYSTEMNAME = "SYSTEMNAME";
	public static final String CLEARONSTARTUP = "CLEARONSTARTUP";
	public static final String SYSTEMLOCKSERVICE = "SystemLockService";
	public static final int LOCK_SEARCH_TYPE_BY_USERNAME = 1;
	public static final int LOCK_SEARCH_TYPE_BY_ITEMNAME = 2;
	public static final int LOCK_SEARCH_TYPE_BY_ALPHALIST = 3;
	private static final String LOCKSQL = "INSERT INTO LOCKS (ITEMNAME, USERNAME, TIMESTAMP, TIMEOUT, SYSTEMNAME, CLEARONSTARTUP) VALUES (?, ?, ?, ?, ?, ?)";
	private static final String UNLOCKSQL = "DELETE FROM LOCKS WHERE ITEMNAME=?";
	private static final String UNLOCKSQLUSER = "DELETE FROM LOCKS WHERE ITEMNAME=? AND USERNAME=?";
	private static final String CLEARNODESQL = "DELETE FROM LOCKS WHERE SYSTEMNAME=? AND USERNAME<>?";
	private static final String TOUCH1SQL = "UPDATE LOCKS SET TIMESTAMP=TIMEOUT+? WHERE ITEMNAME=?";
	private static final String TOUCH11SQL = "UPDATE LOCKS SET TIMESTAMP=TIMEOUT+? WHERE ITEMNAME=? AND USERNAME=?";
	private static final String TOUCH2SQL = "UPDATE LOCKS SET TIMESTAMP=?, TIMEOUT=? WHERE ITEMNAME=?";
	private static final String TOUCH21SQL = "UPDATE LOCKS SET TIMESTAMP=?, TIMEOUT=? WHERE ITEMNAME=? AND USERNAME=?";
	private static final String LISTALLSQL = "SELECT * FROM LOCKS";
	private static final String LIST1SQL = "SELECT * FROM LOCKS WHERE ITEMNAME LIKE ? AND USERNAME=?";
	private static final String LIST2SQL = "SELECT * FROM LOCKS WHERE ITEMNAME LIKE ?";
	private static final String TIMEDOUTSELECTSQL = "SELECT ITEMNAME FROM LOCKS WHERE TIMEOUT>=1 AND TIMESTAMP<=?";
	private static final String TIMEDOUTDELETESQL = "DELETE FROM LOCKS WHERE TIMEOUT>=1 AND TIMESTAMP<=?";
	private static final String STARTUPSQL = "SELECT * FROM LOCKS WHERE SYSTEMNAME=? AND CLEARONSTARTUP=?";
	private static final String TIMERANGESQL = "SELECT COUNT(ITEMNAME), MIN(TIMESTAMP), MAX(TIMESTAMP) FROM LOCKS";
	protected static String dbPool = null;

	public static boolean cluster;

	protected static boolean enableNotifyAll = true;

	protected static boolean remoteNotifyAll = false;
	protected static int remoteNotifyCount = 0;
	protected static int currentRemoteNotifyCount = 0;

	protected static Hashtable localLocks;

	public static boolean useRMILockManager = false;
	public static boolean useFastLocks = false;
	public static boolean profileLocks = false;
	public static String TokenNode = null;
	public static String ThisNode = null;
	protected static long RMILockTimeout = 120000L;
	protected static long RMIWaitTimeout = 300000L;
	protected static String dbTransPool = null;

	protected static long lockWaitTimeout = 1000L;

	protected static String vendor = null;

	static {
		initLockManager();
		if (!FlatUtil.findCentralOpStack()) {
			clearOnStartup();
		}
	}

	protected static void initLockManager() {
		dbTransPool = Manager.getProperty("dbPool");
		vendor = Manager.getVendor();
		Properties props = Manager.getProperties("lockManager");
		if (props == null) {

			LogService.logError("UTIL", "FRAME_LOCK", "ERR_initLockManager");
			return;
		}

		Enumeration propNames = props.propertyNames();
		while (propNames.hasMoreElements()) {
			String name = (String) propNames.nextElement();
			if (name.endsWith(".dbPool")) {
				dbPool = props.getProperty(name);
			}
		}

		if ((dbPool == null) || (dbPool.equals(""))) {
			LogService.logError("UTIL", "FRAME_LOCK", "ERR_initLockManager1");
		}

		String enableN = props.getProperty("enableNotifyAll");
		if (enableN == null) {
			enableNotifyAll = true;
		} else {
			enableNotifyAll = Boolean.valueOf(enableN.trim()).booleanValue();
		}

		String remoteN = props.getProperty("remoteNotifyAll");
		if (remoteN == null) {
			remoteNotifyAll = false;
		} else {
			remoteNotifyAll = Boolean.valueOf(remoteN.trim()).booleanValue();
		}

		String remoteC = props.getProperty("remoteNodifyCount");
		if (remoteC == null) {
			remoteNotifyCount = 0;
		} else {
			remoteNotifyCount = new Integer(remoteC).intValue();
		}

		String clusterProp = Manager.getProperty("cluster");
		if (clusterProp == null) {
			cluster = false;
		} else {
			cluster = Boolean.valueOf(clusterProp).booleanValue();
		}

		if (cluster) {
			String useRMILockManagerString = props
					.getProperty("useRMILockManager");
			if (useRMILockManagerString == null) {
				useRMILockManager = false;
			} else {
				useRMILockManager = Boolean.valueOf(useRMILockManagerString)
						.booleanValue();
				System.out.println(" Lockmanager useRMILockManagr "
						+ useRMILockManager);
			}

			if ((vendor != null) && (vendor.trim().equals("shell"))) {
				useRMILockManager = false;
				System.out
						.println(" Lockmanager useRMILockManagr reset to false");
			}

			String useFastLocksString = props.getProperty("useFastLocks");
			if (useFastLocksString == null) {
				useFastLocks = false;
			} else {
				useFastLocks = Boolean.valueOf(useFastLocksString)
						.booleanValue();
				System.out.println(" Lockmanager useFastLocks" + useFastLocks);
			}
			String timeout = props.getProperty("RMILockTimeout");
			if (timeout != null) {
				RMILockTimeout = Long.valueOf(timeout).longValue();
				System.out.println(" RMILocktimeout = " + RMILockTimeout);
			}
			timeout = props.getProperty("RMIWaitTimeout");
			if (timeout != null) {
				RMIWaitTimeout = Long.valueOf(timeout).longValue();
				System.out.println(" RMIWaittimeout = " + RMIWaitTimeout);
			}

			String profileLocksString = props.getProperty("profileLocks");
			profileLocks = profileLocksString != null ? Boolean.valueOf(
					profileLocksString).booleanValue() : false;
		}
		String wait_timeout = props.getProperty("lockWaitTimeout");
		if (wait_timeout != null) {
			lockWaitTimeout = Long.valueOf(wait_timeout).longValue();
		}

		ThisNode = Manager.getProperty("servername");

		if (!cluster) {
			TokenNode = ThisNode;
		}

		localLocks = new Hashtable();
	}

	protected static void clearOnStartup() {
		Connection conn = null;
		PreparedStatement startup = null;
		PreparedStatement clear = null;
		ResultSet rs = null;
		boolean sqlError = false;
		try {
			String server = Manager.getProperty("servername");
			if (server == null) {
				if (LogService.out.debug) {
					LogService.logDebug("UTIL", "FRAME_LOCK",
							"DEB_clearOnStartup");
				}
			} else {
				conn = getConnection();
				if (conn == null) {
					if (LogService.out.debug) {
						LogService.logDebug("UTIL", "FRAME_LOCK",
								"DEB_clearOnStartup1", new Object[] { ""
										+ server });
					}

				} else {
					conn.setAutoCommit(true);

					startup = conn
							.prepareStatement("SELECT * FROM LOCKS WHERE SYSTEMNAME=? AND CLEARONSTARTUP=?");
					startup.setString(1, server);
					startup.setInt(2, 1);

					rs = startup.executeQuery();

					clear = conn
							.prepareStatement("DELETE FROM LOCKS WHERE ITEMNAME=?");
					while (rs.next()) {
						clear.clearParameters();
						clear.setString(1, rs.getString("ITEMNAME"));
						int ret = clear.executeUpdate();
						if ((ret == 0) && (LogService.out.debug)) {
							LogService.logDebug(
									"UTIL",
									"FRAME_LOCK",
									"DEB_rs_getString",
									new Object[] { ""
											+ rs.getString("ITEMNAME") });
						}
					}
				}
			}
			return;
		} catch (SQLException sqle) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_clearOnStartup",
					sqle);
			sqlError = true;
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (startup != null)
					startup.close();
				if (clear != null)
					clear.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_clearOnStarup", new Object[] { ""
												+ se.getMessage() });
							}
						}
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_clearOnStartup1", sqle);
			}
		}
	}

	protected static Connection getConnection() {
		if (dbPool == null) {

			initLockManager();
		}
		if (dbPool != null) {
			Connection conn = null;
			try {
				conn = JDBCService.getConnection(dbPool);
			} catch (Exception e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_getConnection", e);
			}
			if (conn != null) {
				timeout(conn);
				return conn;
			}
			if (LogService.out.debug) {

				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_getConnection",
						new Object[] { "" + dbPool });
			}

		} else if (LogService.out.debug) {

			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_getConnection1");
		}

		return null;
	}

	private static Connection getTransConnection() {
		Connection conn = null;
		try {
			conn = JDBCService.getConnection(dbTransPool);
		} catch (Exception e) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_getConnection",
					e);
		}
		if (conn != null) {
			timeout(conn);
			return conn;
		}
		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_getConnection",
					new Object[] { "" + dbPool });
		}

		return null;
	}

	protected static void freeConnection(Connection conn) {
		JDBCService.freeConnection(dbPool, conn);
	}

	protected static void freeTransConnection(Connection conn) {
		JDBCService.freeConnection(dbTransPool, conn);
	}

	private static Vector getList(String pattern, String user, boolean detail) {
		return getList(pattern, user, detail, -1);
	}

	private static Vector getList(String pattern, String user, boolean detail,
			int clearonstart) {
		if ((pattern == null) && (user != null)) {
			return null;
		}
		Connection conn = getConnection();
		ResultSet rs = null;
		PreparedStatement prep_stmt = null;
		PreparedStatement pstmt = null;
		boolean sqlError = false;
		boolean useUser = false;
		result = null;
		try {
			if ((pattern == null) && (user == null)) {
				if (clearonstart == -1) {
					prep_stmt = conn.prepareStatement("SELECT * FROM LOCKS");
				} else {
					prep_stmt = conn
							.prepareStatement("SELECT * FROM LOCKS WHERE CLEARONSTARTUP = "
									+ clearonstart);
				}
				rs = prep_stmt.executeQuery();
			} else if ((user == null) && (pattern != null)) {
				if (clearonstart == -1) {
					pstmt = conn
							.prepareStatement("SELECT * FROM LOCKS WHERE ITEMNAME LIKE ?");
				} else {
					pstmt = conn
							.prepareStatement("SELECT * FROM LOCKS WHERE ITEMNAME LIKE ? AND CLEARONSTARTUP = "
									+ clearonstart);
				}
				pstmt.setString(1, pattern);
				rs = pstmt.executeQuery();
			} else {
				if (clearonstart == -1) {
					pstmt = conn
							.prepareStatement("SELECT * FROM LOCKS WHERE ITEMNAME LIKE ? AND USERNAME=?");
				} else {
					pstmt = conn
							.prepareStatement("SELECT * FROM LOCKS WHERE ITEMNAME LIKE ? AND USERNAME=? AND CLEARONSTARTUP = "
									+ clearonstart);
				}
				pstmt.setString(1, pattern);
				pstmt.setString(2, user);
				rs = pstmt.executeQuery();
				useUser = true;
			}

			if (rs != null) {
				result = new Vector();

				while (rs.next()) {
					if (detail) {
						result.addElement(new LockEntry(rs
								.getString("ITEMNAME"), rs
								.getString("USERNAME"),
								rs.getLong("TIMESTAMP"), rs.getLong("TIMEOUT"),
								rs.getString("SYSTEMNAME"), rs
										.getLong("CLEARONSTARTUP")));

					} else {

						result.addElement(rs.getString("ITEMNAME"));
					}
				}
			}

			timeoutLocal();

			Enumeration keys = localLocks.keys();
			String currentKey = null;
			LockEntry le = null;
			String localPattern = pattern.replace('%', ' ').trim();
			while (keys.hasMoreElements()) {
				try {
					currentKey = (String) keys.nextElement();
				} catch (NoSuchElementException ne) {
					break;
				}

				if (((currentKey.indexOf(localPattern) > -1) && (!pattern
						.equals(localPattern)))
						|| ((pattern.equals(localPattern)) && (currentKey
								.equals(pattern)))) {
					if (!useUser) {
						if (detail) {
							result.addElement(localLocks.get(currentKey));
						} else {
							result.addElement(currentKey);
						}
					} else {
						le = (LockEntry) localLocks.get(currentKey);
						if (le.getUser().equals(user)) {
							if (detail) {
								result.addElement(le);
							} else {
								result.addElement(currentKey);
							}
						}
					}
				}
			}

			return result;
		} catch (SQLException sqle) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_getList_String");
			}

			LogService.logError("UTIL", "FRAME_LOCK", "ERR_sqle_getMessage",
					new Object[] { "" + sqle.getMessage() });
			sqlError = true;
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (prep_stmt != null)
					prep_stmt.close();
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_se_getMessage", new Object[] { ""
												+ se.getMessage() });
							}
						}
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] getList(String, String, boolean): SQLException",
								sqle);
			}
		}
	}

	protected static void timeoutLocal() {
		LockEntry le = null;
		String key = null;
		Enumeration e = localLocks.keys();
		long currentTime = System.currentTimeMillis();
		synchronized (localLocks) {
			while (e.hasMoreElements()) {
				key = (String) e.nextElement();

				le = (LockEntry) localLocks.get(key);
				if ((le.getTimeout() > 0L) && (le.getTimeStamp() < currentTime)) {

					localLocks.remove(key);
				}
			}
		}
	}

	private static boolean isUserInLocal(String resource, String user) {
		boolean ret = false;
		LockEntry le = null;

		le = (LockEntry) localLocks.get(resource);
		if ((le != null) && (le.getUser().equals(user))) {
			ret = true;
		}

		return ret;
	}

	private static void timeout(Connection conn) {
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		JDBCProps jprops = null;
		int i = -1;
		int j = -1;
		try {
			jprops = JDBCService.getProps(dbPool);
			if (jprops.dbVendor == 5) {
				pstmt = conn
						.prepareStatement("DELETE FROM LOCKS WHERE TIMEOUT>=1 AND TIMESTAMP<=?");
				pstmt.setLong(1, System.currentTimeMillis() - 1L);
				for (j = 0; j < 1000; j++) {
					try {
						JDBCService.executeUpdate(conn, pstmt);
					} catch (SQLException sqle) {
						if (sqle.getErrorCode() == 1205) {
							if (LogService.out.debug) {
								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_LockManager_timeout");
							}
						} else {
							throw sqle;
						}
					}
				}
			} else {
				pstmt = conn
						.prepareStatement("SELECT ITEMNAME FROM LOCKS WHERE TIMEOUT>=1 AND TIMESTAMP<=?");
				pstmt.setLong(1, System.currentTimeMillis() - 1L);
				rs = JDBCService.executeQuery(conn, pstmt);
				ArrayList list = new ArrayList();
				while (rs.next()) {
					list.add(rs.getString(1));
				}
				rs.close();
				rs = null;
				pstmt.close();
				pstmt = null;
				Object[] ary = list.toArray();
				pstmt = conn
						.prepareStatement("DELETE FROM LOCKS WHERE ITEMNAME=?");
				for (i = 0; i < ary.length; i++) {
					pstmt.clearParameters();
					pstmt.setString(1, (String) ary[i]);
					for (j = 0; j < 1000; j++) {
						try {
							JDBCService.executeUpdate(conn, pstmt);
						} catch (SQLException sqle) {
							if (sqle.getErrorCode() == 1213) {
								if (LogService.out.debug) {
									LogService.logDebug("UTIL", "FRAME_LOCK",
											"DEB_LockManager_timeout1");
								}
							} else
								throw sqle;
						}
					}
				}
			}
			return;
		} catch (SQLException sqle) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_NO_MSG_EXCEP",
					sqle);
			return;
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (pstmt != null)
					pstmt.close();
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_timeout_Connection", sqle);
			}
		}
	}

	public static boolean doLock(String resource, String user, long timeout,
			boolean clearOnStartup, boolean local, boolean inTransaction) {
		return doLock(resource, user, timeout, clearOnStartup, local, false,
				Manager.getProperty("servername").toLowerCase(), inTransaction);
	}

	public static boolean doLock(String resource, String user, long timeout,
			boolean clearOnStartup, boolean local, boolean EDISpecial,
			String origNodeName, boolean inTransaction) {
		boolean ret = false;
		long long_clearOnStartup = 0L;

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager2",
					new Object[] { "" + resource, "" + user, "" + timeout,
							"" + clearOnStartup, "" + local });
		}

		if (((cluster) && (EDISpecial) && (useFastLocks) && (local))
				|| ((!cluster) && (local))) {
			if ((cluster) && (useFastLocks) && (useRMILockManager)) {
				timeout = RMILockTimeout;
			}
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_timeout_timeout", new Object[] { "" + timeout });
			}

			if (clearOnStartup) {
				long_clearOnStartup = 1L;
			}
			timeoutLocal();

			if (localLocks.containsKey(resource)) {
				return false;
			}
			synchronized (localLocks) {

				if (localLocks.containsKey(resource)) {
					return false;
				}
				String origLockServer = null;
				if (cluster) {
					origLockServer = origNodeName;
				} else {
					origLockServer = Manager.getProperty("servername")
							.toLowerCase();
				}
				localLocks.put(
						resource,
						new LockEntry(resource, user, System
								.currentTimeMillis() + timeout, timeout,
								origLockServer, long_clearOnStartup));
			}

			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_resource",
						new Object[] { "" + resource });

				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_localLocks_localLocks2", new Object[] { ""
								+ localLocks.toString() });
			}
			ret = true;
		} else {
			if ((EDISpecial) && (useRMILockManager) && (cluster)) {
				timeout = RMILockTimeout;
			}
			ret = doLockDBEDISpecial(resource, user, timeout, clearOnStartup,
					origNodeName, inTransaction);
		}
		return ret;
	}

	public static boolean doLockDBEDISpecial(String resource, String user,
			long timeout, boolean clearOnStartup, String origNodeName,
			boolean inTransaction) {
		if ((resource == null) || (resource.equals("")))
			return false;
		if ((user == null) || (user.equals(""))) {
			return false;
		}
		boolean sqlError = false;
		Connection conn = null;
		if (inTransaction) {
			conn = getTransConnection();
		} else {
			conn = getConnection();
		}
		if (conn == null) {
			return false;
		}

		int ret = 0;

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager3");
		}

		PreparedStatement pstmt = null;

		try {
			pstmt = conn
					.prepareStatement("INSERT INTO LOCKS (ITEMNAME, USERNAME, TIMESTAMP, TIMEOUT, SYSTEMNAME, CLEARONSTARTUP) VALUES (?, ?, ?, ?, ?, ?)");
			pstmt.setString(1, resource);
			pstmt.setString(2, user);
			pstmt.setLong(3, System.currentTimeMillis() + timeout);
			pstmt.setLong(4, timeout);

			pstmt.setString(5, origNodeName);
			if (clearOnStartup) {
				pstmt.setInt(6, 1);
			} else {
				pstmt.setInt(6, 0);
			}
			ret = pstmt.executeUpdate();

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_se_getMessage1", new Object[] { ""
												+ se.getMessage() });
							}
						}

						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else {
						freeConnection(conn);
					}
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] doLock(String, String, long, boolean): SQLException",
								sqle);
			}

			if (ret != 0) {
				break label667;
			}
		} catch (SQLException sqle) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_sqle_getMessage",
						new Object[] { "" + sqle.getMessage() });
			}

			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_se_getMessage1", new Object[] { ""
												+ se.getMessage() });
							}
						}

						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else {
						freeConnection(conn);
					}
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] doLock(String, String, long, boolean): SQLException",
								sqle);
			}
		}

		if (LogService.out.debug) {

			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager4",
					new Object[] { "" + resource, "" + dbPool });
		}
		return false;

		label667: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager5");
		}

		return true;
	}

	public static boolean doLock(String resource, String user, long timeout,
			boolean clearOnStartup, boolean inTransaction) {
		if ((resource == null) || (resource.equals("")))
			return false;
		if ((user == null) || (user.equals(""))) {
			return false;
		}

		boolean sqlError = false;
		Connection conn = null;

		if (inTransaction) {
			conn = getTransConnection();
		} else
			conn = getConnection();
		if (conn == null) {
			return false;
		}

		int ret = 0;

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager31");
		}

		PreparedStatement pstmt = null;
		try {
			pstmt = conn
					.prepareStatement("INSERT INTO LOCKS (ITEMNAME, USERNAME, TIMESTAMP, TIMEOUT, SYSTEMNAME, CLEARONSTARTUP) VALUES (?, ?, ?, ?, ?, ?)");
			pstmt.setString(1, resource);
			pstmt.setString(2, user);
			pstmt.setLong(3, System.currentTimeMillis() + timeout);
			pstmt.setLong(4, timeout);
			pstmt.setString(5, Manager.getProperty("servername").toLowerCase());

			if (clearOnStartup) {
				pstmt.setInt(6, 1);
			} else {
				pstmt.setInt(6, 0);
			}
			ret = pstmt.executeUpdate();

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_se_getMessage11",
										new Object[] { "" + se.getMessage() });
							}
						}

						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else {
						freeConnection(conn);
					}
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] doLock(String, String, long, boolean): SQLException",
								sqle);
			}

			if (ret != 0) {
				break label673;
			}
		} catch (SQLException sqle) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_sqle_getMessage1",
						new Object[] { "" + sqle.getMessage() });
			}

			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_se_getMessage11",
										new Object[] { "" + se.getMessage() });
							}
						}

						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else {
						freeConnection(conn);
					}
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] doLock(String, String, long, boolean): SQLException",
								sqle);
			}
		}

		if (LogService.out.debug) {

			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager41",
					new Object[] { "" + resource, "" + dbPool });
		}
		return false;

		label673: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager51");
		}

		return true;
	}

	public static boolean lock(String resource, String user, boolean local,
			long timeout) {
		return doLock(resource, user, timeout, false, local, false);
	}

	public static boolean lock(String resource, String user, long timeout) {
		return doLock(resource, user, timeout, false, false);
	}

	private static void waitObjects(long wait_timeout) {
		synchronized ("wait") {
			try {
				"wait".wait(wait_timeout);
			} catch (IllegalArgumentException e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_IllegalArgumentException", e);
			} catch (IllegalMonitorStateException e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_IllegalMonitorStateException", e);
			} catch (InterruptedException e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_InterruptedException", e);
			} catch (Exception e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_LockManager2", e);
			}
		}
	}

	public static void remoteNotifyAllObjects() {
		synchronized ("wait") {
			try {
				"wait".notifyAll();
			} catch (IllegalMonitorStateException e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_IllegalMonitorStateException1", e);
			} catch (Exception e) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_notifyAllObjects", e);
			}
		}
	}

	protected static void notifyAllObjects() {
		if (enableNotifyAll) {
			synchronized ("wait") {
				try {
					"wait".notifyAll();
				} catch (IllegalMonitorStateException e) {
					LogService.logException("UTIL", "FRAME_LOCK",
							"ERR_IllegalMonitorStateException11", e);
				} catch (Exception e) {
					LogService.logException("UTIL", "FRAME_LOCK",
							"ERR_notifyAllObjects1", e);
				}
			}
		}
	}

	public static boolean lock(String resource, String user, boolean local,
			long timeout, boolean EDISpecial, long wait_timeout,
			String origNodename) {
		return lock(resource, user, local, timeout, EDISpecial, wait_timeout,
				origNodename, false);
	}

	public static boolean lock(String resource, String user, boolean local,
			long timeout, boolean EDISpecial, long wait_timeout,
			String origNodename, boolean clearOnStartup) {
		boolean isLocked = true;
		long sum = 0L;
		try {
			while (!doLock(resource, user, timeout, clearOnStartup, local,
					EDISpecial, origNodename, false)) {
				waitObjects(lockWaitTimeout);
				sum += lockWaitTimeout;
				if (sum >= wait_timeout) {
					isLocked = false;
				}
			}
		} catch (Exception e) {
			isLocked = false;
			LogService
					.logException("UTIL", "FRAME_LOCK", "ERR_LockManager3", e);
		}
		return isLocked;
	}

	public static boolean lock(String resource, String user, long timeout,
			long wait_timeout, boolean local) {
		boolean ret = true;
		long sum = 0L;
		try {
			while (!lock(resource, user, local, timeout)) {
				waitObjects(lockWaitTimeout);
				sum += lockWaitTimeout;
				if (sum >= wait_timeout) {
					ret = false;
				}
			}

			return ret;
		} catch (Exception e) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_LockManager31",
					e);
		}
		return false;
	}

	public static boolean lock(String resource, String user, long timeout,
			long wait_timeout) {
		return lock(resource, user, false, timeout, wait_timeout);
	}

	public static boolean lock(String resource, String user,
			boolean clearOnStartup, long timeout, long wait_timeout) {
		boolean ret = true;
		long sum = 0L;
		try {
			while (!doLock(resource, user, timeout, clearOnStartup, false)) {
				waitObjects(lockWaitTimeout);
				sum += lockWaitTimeout;
				if (sum >= wait_timeout) {
					ret = false;
				}
			}
		} catch (Exception e) {
			ret = false;
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_LockManager32",
					e);
		}

		return ret;
	}

	public static boolean lock(String resource, String user, long timeout,
			boolean clearOnStartup, boolean local) {
		return doLock(resource, user, timeout, clearOnStartup, local, false);
	}

	public static boolean lock(String resource, String user, long timeout,
			boolean clearOnStartup) {
		return doLock(resource, user, timeout, clearOnStartup, false);
	}

	public static boolean lockInTrans(String resource, String user,
			long timeout, long wait_timeout, boolean local) {
		boolean ret = true;
		long sum = 0L;
		try {
			while (!lockInTrans(resource, user, timeout, local, false)) {
				waitObjects(lockWaitTimeout);
				sum += lockWaitTimeout;
				if (sum >= wait_timeout) {
					ret = false;
				}
			}

			return ret;
		} catch (Exception e) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_LockManager31",
					e);
		}
		return false;
	}

	public static boolean lockInTrans(String resource, String user,
			long timeout, boolean clearOnStartup, boolean local) {
		return doLock(resource, user, timeout, clearOnStartup, local, true);
	}

	public static boolean lockInTrans(String resource, String user,
			long timeout, boolean clearOnStartup) {
		return doLock(resource, user, timeout, clearOnStartup, true);
	}

	public static boolean unlock(String resource, String user,
			boolean inTransaction) {
		boolean ret = doUnlock(resource, user, inTransaction);
		notifyAllObjects();
		return ret;
	}

	public static boolean unlock(String resource, boolean inTransaction) {
		boolean ret = doUnlock(resource, inTransaction);
		notifyAllObjects();
		return ret;
	}

	public static boolean unlock(String resource, String user) {
		boolean ret = doUnlock(resource, user, false);
		notifyAllObjects();
		return ret;
	}

	public static boolean unlock(String resource) {
		boolean ret = doUnlock(resource, false);
		notifyAllObjects();
		return ret;
	}

	public static boolean doUnlock(String resource, boolean inTransaction) {
		if ((resource == null) || (resource.equals(""))) {
			return false;
		}

		timeoutLocal();
		if (localLocks.remove(resource) != null) {
			if (LogService.out.debug)
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_unlocked",
						new Object[] { "" + resource });
			return true;
		}
		boolean sqlError = false;
		Connection conn = null;
		if (inTransaction) {
			conn = getTransConnection();
		} else
			conn = getConnection();
		if (conn == null)
			return false;
		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager6");
		}
		int ret = 0;

		PreparedStatement pstmt = null;
		try {
			pstmt = conn.prepareStatement("DELETE FROM LOCKS WHERE ITEMNAME=?");
			pstmt.setString(1, resource);

			for (int j = 0; j < 1000; j++) {
				try {
					ret = pstmt.executeUpdate();
				} catch (SQLException sqle) {
					if (sqle.getErrorCode() == 1213) {
						if (LogService.out.debug) {
							LogService.logDebug("UTIL", "FRAME_LOCK",
									"DEB_LockManager_timeout2");
						}
					} else {
						throw sqle;
					}
				}
			}

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_unLock_String", new Object[] { ""
												+ se.getMessage() });
							}
						}
						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else {
						freeConnection(conn);
					}
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_unlock_String", sqle);
			}

			if (ret != 0) {
				break label648;
			}
		} catch (SQLException sqle) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_NO_MSG_EXCEP1",
					sqle);
			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							if (LogService.out.debug) {

								LogService.logDebug("UTIL", "FRAME_LOCK",
										"DEB_unLock_String", new Object[] { ""
												+ se.getMessage() });
							}
						}
						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else {
						freeConnection(conn);
					}
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_unlock_String", sqle);
			}
		}

		if (LogService.out.debug) {

			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_unlock_String",
					new Object[] { "" + resource, "" + dbPool });
		}
		return false;

		label648: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager7");
		}

		return true;
	}

	public static boolean doUnlock(String resource, String user,
			boolean inTransaction) {
		if ((resource == null) || (resource.equals(""))) {
			return false;
		}

		if ((user == null) || (user.equals(""))) {
			return false;
		}

		timeoutLocal();
		if (isUserInLocal(resource, user)) {
			if (localLocks.remove(resource) != null) {
				if (LogService.out.debug) {
					LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_unlocked1",
							new Object[] { "" + resource, "" + user });
				}
				return true;
			}
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_unlocked2",
						new Object[] { "" + resource, "" + user });
			}
			return false;
		}

		boolean sqlError = false;
		Connection conn = null;
		if (inTransaction) {
			conn = getTransConnection();
		} else
			conn = getConnection();
		if (conn == null) {
			return false;
		}
		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager61");
		}
		int ret = 0;

		PreparedStatement pstmt = null;
		try {
			pstmt = conn
					.prepareStatement("DELETE FROM LOCKS WHERE ITEMNAME=? AND USERNAME=?");
			pstmt.setString(1, resource);
			pstmt.setString(2, user);

			for (int j = 0; j < 1000; j++) {
				try {
					ret = pstmt.executeUpdate();
				} catch (SQLException sqle) {
					if (sqle.getErrorCode() == 1213) {
						if (LogService.out.debug) {
							LogService.logDebug("UTIL", "FRAME_LOCK",
									"DEB_LockManager_timeout3");
						}
					} else {
						throw sqle;
					}
				}
			}

			try {
				if (pstmt != null) {
					pstmt.close();
				}
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException(
									"[LockManager] unLock(String, String): rollback SQLException "
											+ se.getMessage(), se);
						}
						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else
						freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException(
						"[LockManager] unlock(String, String): SQLException",
						sqle);
			}

			if (ret != 0) {
				break label715;
			}
		} catch (SQLException sqle) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_NO_MSG_EXCEP2",
					sqle);
			sqlError = true;
		} finally {
			try {
				if (pstmt != null) {
					pstmt.close();
				}
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException(
									"[LockManager] unLock(String, String): rollback SQLException "
											+ se.getMessage(), se);
						}
						ret = 0;
					}
					if (inTransaction) {
						freeTransConnection(conn);
					} else
						freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException(
						"[LockManager] unlock(String, String): SQLException",
						sqle);
			}
		}

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager8",
					new Object[] { "" + resource, "" + dbPool });
		}
		return false;

		label715: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager71");
		}

		return true;
	}

	public static boolean doClearNode(String servername) {
		return doClearNode(servername, -1);
	}

	public static boolean doClearNode(String servername, int clearonstart) {
		if ((servername == null) || (servername.length() <= 0)) {
			return false;
		}

		boolean sqlError = false;
		Connection conn = getConnection();
		if (conn == null) {
			return false;
		}

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_doClearNode");
		}
		int ret = 0;
		String _sqlstr = "DELETE FROM LOCKS WHERE SYSTEMNAME=? AND USERNAME<>?";

		PreparedStatement pstmt = null;
		try {
			if (clearonstart != -1) {
				_sqlstr = _sqlstr + " AND CLEARONSTARTUP = ? ";
			}
			pstmt = conn.prepareStatement(_sqlstr);
			pstmt.setString(1, servername);
			pstmt.setString(2, "SystemLockService");
			if (clearonstart != -1) {
				pstmt.setInt(3, clearonstart);
			}
			ret = pstmt.executeUpdate();

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException("UTIL", "FRAME_LOCK",
									"ERR_doClearNode_String", new Object[] { ""
											+ se.getMessage() }, se);
						}
						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_doClearNode_String1", sqle);
			}

			if (localLocks == null) {
				break label643;
			}
		} catch (SQLException sqle) {
			LogService.logException("UTIL", "FRAME_LOCK", "ERR_NO_MSG_EXCEP3",
					sqle);
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_sqle_getMessage2",
						new Object[] { "" + sqle.getMessage() });
			}
			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException("UTIL", "FRAME_LOCK",
									"ERR_doClearNode_String", new Object[] { ""
											+ se.getMessage() }, se);
						}
						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_doClearNode_String1", sqle);
			}
		}

		Enumeration e = localLocks.keys();
		synchronized (localLocks) {
			while (e.hasMoreElements()) {
				String key = (String) e.nextElement();
				LockEntry le = (LockEntry) localLocks.get(key);
				if (le.getSystemName().equals(servername)) {
					LogService.log("UTIL", "FRAME_LOCK",
							"INFO_doClearNode_String", new Object[] { "" + key,
									"" + servername });
					localLocks.remove(key);
				}
			}
		}
		label643: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager9");
		}

		return true;
	}

	public static boolean touch(String resource) {
		return doTouch(resource);
	}

	public static boolean doTouch(String resource) {
		return doTouch(resource, null);
	}

	public static boolean doTouch(String resource, String user) {
		if ((resource == null) || (resource.equals(""))) {
			return false;
		}

		LockEntry le = (LockEntry) localLocks.get(resource);
		if (le != null) {
			le.setTimeStamp(System.currentTimeMillis() + le.getTimeout());
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_resource1",
						new Object[] { "" + resource });
			}
			return true;
		}

		boolean sqlError = false;
		Connection conn = getConnection();
		if (conn == null) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager10");
			}
			return false;
		}

		int ret = 0;
		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager11");
		}

		PreparedStatement pstmt = null;
		try {
			if (user == null) {
				pstmt = conn
						.prepareStatement("UPDATE LOCKS SET TIMESTAMP=TIMEOUT+? WHERE ITEMNAME=?");
				pstmt.setLong(1, System.currentTimeMillis());
				pstmt.setString(2, resource);
			} else {
				pstmt = conn
						.prepareStatement("UPDATE LOCKS SET TIMESTAMP=TIMEOUT+? WHERE ITEMNAME=? AND USERNAME=?");
				pstmt.setLong(1, System.currentTimeMillis());
				pstmt.setString(2, resource);
				pstmt.setString(3, user);
			}
			ret = pstmt.executeUpdate();

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException("UTIL", "FRAME_LOCK",
									"ERR_se_getMessage1", new Object[] { ""
											+ se.getMessage() }, se);
						}
						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_LockManager5", sqle);
			}

			if (ret != 0) {
				break label748;
			}
		} catch (SQLException sqle) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_sqle_getMessage3",
						new Object[] { "" + sqle.getMessage() });
			}
			LogService.logError("UTIL", "FRAME_LOCK", "ERR_sqlrror_sqle",
					new Object[] { "" + sqle.getMessage() });
			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException("UTIL", "FRAME_LOCK",
									"ERR_se_getMessage1", new Object[] { ""
											+ se.getMessage() }, se);
						}
						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_LockManager5", sqle);
			}
		}

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager12",
					new Object[] { "" + resource, "" + dbPool });
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager13",
					new Object[] { "" + resource });
		}
		return false;

		label748: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager14");
		}
		return true;
	}

	public static boolean touch(String resource, long timeout) {
		return doTouch(resource, timeout);
	}

	public static boolean doTouch(String resource, long timeout) {
		return doTouch(resource, timeout, null);
	}

	public static boolean doTouch(String resource, long timeout, String user) {
		if ((resource == null) || (resource.equals(""))) {
			return false;
		}

		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager111");
		}

		LockEntry le = (LockEntry) localLocks.get(resource);
		if (le != null) {
			le.setTimeStamp(System.currentTimeMillis() + timeout);
			le.setTimeout(timeout);

			if (LogService.out.debug)
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_resource11",
						new Object[] { "" + resource });
			return true;
		}

		boolean sqlError = false;
		Connection conn = getConnection();
		if (conn == null) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_lock_doTouch");
			}
			return false;
		}

		int ret = 0;

		PreparedStatement pstmt = null;
		try {
			if (user == null) {
				pstmt = conn
						.prepareStatement("UPDATE LOCKS SET TIMESTAMP=?, TIMEOUT=? WHERE ITEMNAME=?");
				pstmt.setLong(1, System.currentTimeMillis() + timeout);
				pstmt.setLong(2, timeout);
				pstmt.setString(3, resource);
			} else {
				pstmt = conn
						.prepareStatement("UPDATE LOCKS SET TIMESTAMP=?, TIMEOUT=? WHERE ITEMNAME=? AND USERNAME=?");
				pstmt.setLong(1, System.currentTimeMillis() + timeout);
				pstmt.setLong(2, timeout);
				pstmt.setString(3, resource);
				pstmt.setString(4, user);
			}
			ret = pstmt.executeUpdate();

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException(
									"[LockManager] touch(String, long): rollback SQLException "
											+ se.getMessage(), se);
						}
						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] touch(String, long): SQLException",
								sqle);
			}

			if (ret != 0) {
				break label758;
			}
		} catch (SQLException sqle) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_sqle_getMessage4",
						new Object[] { "" + sqle.getMessage() });
			}

			LogService.logError("UTIL", "FRAME_LOCK", "ERR_sqle_getMessage1",
					new Object[] { "" + sqle.getMessage() });
			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException(
									"[LockManager] touch(String, long): rollback SQLException "
											+ se.getMessage(), se);
						}
						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] touch(String, long): SQLException",
								sqle);
			}
		}

		if (LogService.out.debug) {

			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager15",
					new Object[] { "" + resource, "" + dbPool });
		}

		LogService.logError("UTIL", "FRAME_LOCK", "ERR_LockManager7",
				new Object[] { "" + resource, "" + dbPool });
		return false;

		label758: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager141");
		}

		return true;
	}

	public static Vector list() {
		return getList(null, null, false);
	}

	public static Vector list(String pattern, String user) {
		return getList(pattern, user, false);
	}

	public static Vector listDetail() {
		return getList(null, null, true);
	}

	public static Vector listDetail(String pattern, String user) {
		return getList(pattern, user, true);
	}

	public static Vector listByResourcePrefix(String prefix) {
		return getList(prefix + "%", null, false);
	}

	public static Vector listByResourcePrefix(String prefix, int clearonstart) {
		return getList(prefix + "%", null, false, clearonstart);
	}

	public static boolean isLocked(String resource) {
		Vector locked = getList(resource, null, false);
		if ((locked == null) || (locked.isEmpty())) {
			return false;
		}

		return true;
	}

	public static boolean isLocked(String resource, String user) {
		Vector locked = getList(resource, user, false);
		if ((locked == null) || (locked.isEmpty())) {
			return false;
		}

		return true;
	}

	public static Vector listLocalLocks(String username, String resourcename,
			long startTime, long endTime, boolean searchStartsWith) {
		Vector v = new Vector();

		Enumeration keys = localLocks.keys();
		while (keys.hasMoreElements()) {
			String currentKey = (String) keys.nextElement();
			if ((username == null) && (resourcename == null)) {
				if ((startTime > 0L) && (endTime > 0L)) {
					LockEntry entry = (LockEntry) localLocks.get(currentKey);
					if ((entry.getTimeStamp() >= startTime)
							&& (entry.getTimeStamp() <= endTime)) {
						v.add(localLocks.get(currentKey));
					}
				} else {
					v.add(localLocks.get(currentKey));
				}
			} else if ((username != null) && (resourcename == null)) {
				LockEntry entry = (LockEntry) localLocks.get(currentKey);
				if (searchStartsWith) {
					if (entry.getUser().toLowerCase()
							.startsWith(username.toLowerCase())) {
						if ((startTime > 0L) && (endTime > 0L)) {
							if ((entry.getTimeStamp() >= startTime)
									&& (entry.getTimeStamp() <= endTime)) {
								v.add(localLocks.get(currentKey));
							}
						} else {
							v.add(localLocks.get(currentKey));
						}
					}
				} else if (entry.getUser().toLowerCase()
						.indexOf(username.toLowerCase()) != -1) {
					if ((startTime > 0L) && (endTime > 0L)) {
						if ((entry.getTimeStamp() >= startTime)
								&& (entry.getTimeStamp() <= endTime)) {
							v.add(localLocks.get(currentKey));
						}
					} else {
						v.add(localLocks.get(currentKey));
					}
				}
			} else if ((username == null) && (resourcename != null)) {
				if (searchStartsWith) {
					if (currentKey.toLowerCase().startsWith(
							resourcename.toLowerCase())) {
						if ((startTime > 0L) && (endTime > 0L)) {
							LockEntry entry = (LockEntry) localLocks
									.get(currentKey);
							if ((entry.getTimeStamp() >= startTime)
									&& (entry.getTimeStamp() <= endTime)) {
								v.add(localLocks.get(currentKey));
							}
						} else {
							v.add(localLocks.get(currentKey));
						}
					}
				} else if (currentKey.toLowerCase().indexOf(
						resourcename.toLowerCase()) != -1) {
					if ((startTime > 0L) && (endTime > 0L)) {
						LockEntry entry = (LockEntry) localLocks
								.get(currentKey);
						if ((entry.getTimeStamp() >= startTime)
								&& (entry.getTimeStamp() <= endTime)) {
							v.add(localLocks.get(currentKey));
						}
					} else {
						v.add(localLocks.get(currentKey));
					}
				}
			} else {
				LockEntry entry = (LockEntry) localLocks.get(currentKey);
				if (searchStartsWith) {
					if ((entry.getUser().toLowerCase().startsWith(username
							.toLowerCase()))
							&& (currentKey.toLowerCase()
									.startsWith(resourcename.toLowerCase()))) {
						if ((startTime > 0L) && (endTime > 0L)) {
							if ((entry.getTimeStamp() >= startTime)
									&& (entry.getTimeStamp() <= endTime)) {
								v.add(localLocks.get(currentKey));
							}
						} else {
							v.add(localLocks.get(currentKey));
						}
					}
				} else if ((entry.getUser().toLowerCase()
						.indexOf(username.toLowerCase()) != -1)
						&& (currentKey.toLowerCase().indexOf(
								resourcename.toLowerCase()) != -1)) {
					if ((startTime > 0L) && (endTime > 0L)) {
						if ((entry.getTimeStamp() >= startTime)
								&& (entry.getTimeStamp() <= endTime)) {
							v.add(localLocks.get(currentKey));
						}
					} else {
						v.add(localLocks.get(currentKey));
					}
				}
			}
		}

		return v;
	}

	public static Vector listLocks() throws SQLException {
		return listLocks(null, "USERNAME ASC");
	}

	public static Vector listLocks(int searchType, String searchTerm,
			String orderBy, String order) throws SQLException {
		Vector v = null;

		String escapeString = JDBCService.getNamedSQL(dbPool, "escapeString");
		if (escapeString == null) {
			escapeString = "";
		}
		String sqlQuery = null;
		switch (searchType) {
		case 1:
			sqlQuery = "SELECT * FROM LOCKS WHERE UPPER(USERNAME) LIKE ? "
					+ escapeString + " ORDER BY " + orderBy.toUpperCase() + " "
					+ order;

			break;
		case 2:
			sqlQuery = "SELECT * FROM LOCKS WHERE UPPER(ITEMNAME) LIKE ? "
					+ escapeString + " ORDER BY " + orderBy.toUpperCase() + " "
					+ order;

			break;
		case 3:
			if (orderBy.equalsIgnoreCase("username")) {
				sqlQuery = "SELECT * FROM LOCKS WHERE UPPER(USERNAME) LIKE ? "
						+ escapeString + " ORDER BY " + orderBy.toUpperCase()
						+ " " + order;
			} else {
				sqlQuery = "SELECT * FROM LOCKS WHERE UPPER(ITEMNAME) LIKE ? "
						+ escapeString + " ORDER BY " + orderBy.toUpperCase()
						+ " " + order;
			}
			break;
		default:
			throw new SQLException("Unsupported searchType");
		}

		return searchListLocks(sqlQuery, searchTerm.toUpperCase());
	}

	public static Vector listLocks(String where, String orderBy)
			throws SQLException {
		Vector v = null;
		StringBuffer buf = new StringBuffer("SELECT * FROM LOCKS");

		if (LogService.out.debug) {
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager16");
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager17",
					new Object[] { "" + where });
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager18",
					new Object[] { "" + orderBy });
		}

		if ((where != null) && (where.length() > 0)) {
			buf.append(" WHERE ");
			buf.append(where.toUpperCase());
			if (where.indexOf("\\%") != -1) {
				buf.append(" ");
				buf.append(JDBCService.getNamedSQL(dbPool, "escapeString"));

				buf.append(" ");
			}
		}

		if ((orderBy != null) && (orderBy.length() > 0)) {
			buf.append(" ORDER BY ");
			buf.append(orderBy.toUpperCase());
		}

		if (LogService.out.debug) {
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager19",
					new Object[] { "" + buf });
		}

		return searchListLocks(buf.toString(), null);
	}

	public static Vector listLocks(long startDateTime, long endDateTime,
			String orderBy, String order) throws SQLException {
		Vector v = null;
		String sqlQuery = "SELECT * FROM LOCKS WHERE TIMESTAMP >= "
				+ startDateTime + " AND TIMESTAMP <= " + endDateTime
				+ " ORDER BY " + orderBy.toUpperCase() + " " + order;

		if (LogService.out.debug) {
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager161");
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_startDateTime",
					new Object[] { "" + startDateTime });
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager20",
					new Object[] { "" + endDateTime });
			LogService.out.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager181",
					new Object[] { "" + orderBy });
		}

		if ((startDateTime > 0L) && (endDateTime > 0L)
				&& (startDateTime <= endDateTime)) {
			v = searchListLocks(sqlQuery, null);
		}

		return v;
	}

	public static long getTimeRange(Vector timeperiod) {
		Connection conn = null;
		ResultSet rs = null;
		PreparedStatement prep_stmt = null;
		total = 0L;
		boolean sqlError = false;
		try {
			conn = getConnection();
			prep_stmt = conn
					.prepareStatement("SELECT COUNT(ITEMNAME), MIN(TIMESTAMP), MAX(TIMESTAMP) FROM LOCKS");
			rs = prep_stmt.executeQuery();

			if (LogService.out.debug) {
				LogService.out.logDebug("UTIL", "FRAME_LOCK",
						"DEB_LockManager21");
			}

			if (rs != null) {
				if ((rs.next()) && (rs.getLong(1) > 0L)) {
					total = rs.getLong(1);

					Date startTime = new Date(rs.getLong(2));
					Date endTime = new Date(rs.getLong(3));

					Calendar cal = Calendar.getInstance();
					cal.setTime(startTime);
					int min = cal.get(12);
					cal.set(12, min - 1);
					startTime = cal.getTime();
					timeperiod.addElement(startTime);
					cal.setTime(endTime);
					cal.add(12, 1);
					endTime = cal.getTime();
					timeperiod.addElement(endTime);
				}
				rs.close();
			}

			return total;
		} catch (SQLException sqe) {
			LogService.out.logException("UTIL", "FRAME_LOCK",
					"ERR_LockManager_getTimeRange", sqe);
			sqlError = true;
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (prep_stmt != null)
					prep_stmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException("UTIL", "FRAME_LOCK",
									"ERR_getTimeRange",
									new Object[] { "" + se.getMessage() }, se);
						}
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_getTimeRange1", sqle);
			}
		}
	}

	public static boolean lock(String resource, String user, long timeout,
			boolean clearOnStartup, String servername) {
		if ((resource == null) || (resource.equals("")))
			return false;
		if ((user == null) || (user.equals("")))
			return false;
		if ((servername == null) || (servername.equals(""))) {
			return false;
		}

		boolean sqlError = false;
		Connection conn = getConnection();
		if (conn == null) {
			return false;
		}

		int ret = 0;
		if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager32");
		}

		PreparedStatement pstmt = null;
		try {
			pstmt = conn
					.prepareStatement("INSERT INTO LOCKS (ITEMNAME, USERNAME, TIMESTAMP, TIMEOUT, SYSTEMNAME, CLEARONSTARTUP) VALUES (?, ?, ?, ?, ?, ?)");
			pstmt.setString(1, resource);
			pstmt.setString(2, user);
			pstmt.setLong(3, System.currentTimeMillis() + timeout);
			pstmt.setLong(4, timeout);
			pstmt.setString(5, servername);
			if (clearOnStartup) {
				pstmt.setInt(6, 1);
			} else {
				pstmt.setInt(6, 0);
			}
			ret = pstmt.executeUpdate();

			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService
									.logException(
											"[LockManager] doLock(String, String, long, boolean, String): rollbackSQLException "
													+ se.getMessage(), se);
						}

						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] doLock(String, String, long, boolean, String): SQLException",
								sqle);
			}

			if (ret != 0) {
				break label601;
			}
		} catch (SQLException sqle) {
			if (LogService.out.debug) {
				LogService.logDebug("UTIL", "FRAME_LOCK",
						"DEB_sqle_getMessage5",
						new Object[] { "" + sqle.getMessage() });
			}
			sqlError = true;
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService
									.logException(
											"[LockManager] doLock(String, String, long, boolean, String): rollbackSQLException "
													+ se.getMessage(), se);
						}

						ret = 0;
					}
					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService
						.logException(
								"[LockManager] doLock(String, String, long, boolean, String): SQLException",
								sqle);
			}
		}

		if (LogService.out.debug) {
			LogService
					.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager22",
							new Object[] { "" + resource, "" + servername,
									"" + dbPool });
		}
		return false;
		label601: if (LogService.out.debug) {
			LogService.logDebug("UTIL", "FRAME_LOCK", "DEB_LockManager52");
		}

		return true;
	}

	private static Vector searchListLocks(String sqlQuery, String parameter)
			throws SQLException {
		Connection conn = null;
		ResultSet rs = null;
		PreparedStatement pstmt = null;
		v = null;
		boolean sqlError = false;
		try {
			conn = getConnection();
			if (conn != null) {
				v = new Vector();
				pstmt = conn.prepareStatement(sqlQuery);
				if (parameter != null)
					pstmt.setString(1, parameter);
				rs = pstmt.executeQuery();

				while (rs.next()) {
					v.addElement(new LockEntry(rs.getString("ITEMNAME"), rs
							.getString("USERNAME"), rs.getLong("TIMESTAMP"), rs
							.getLong("TIMEOUT"), rs.getString("SYSTEMNAME"), rs
							.getLong("CLEARONSTARTUP")));
				}
			}

			return v;
		} catch (SQLException sqe) {
			LogService.out.logException("UTIL", "FRAME_LOCK", "ERR_listLocks",
					sqe);
			sqlError = true;
			throw sqe;
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (pstmt != null)
					pstmt.close();
				if (conn != null) {
					if (sqlError) {
						try {
							conn.rollback();
						} catch (SQLException se) {
							LogService.logException("UTIL", "FRAME_LOCK",
									"ERR_se_getMessage3", new Object[] { ""
											+ se.getMessage() }, se);
						}
					}

					freeConnection(conn);
				}
			} catch (SQLException sqle) {
				LogService.logException("UTIL", "FRAME_LOCK",
						"ERR_LockManager8", sqle);
			}
		}
	}

	public AFCLockManager() {
	}
}
