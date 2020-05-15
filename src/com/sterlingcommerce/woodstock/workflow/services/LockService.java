package com.sterlingcommerce.woodstock.workflow.services;

import com.sterlingcommerce.woodstock.ops.SendOpsCommand;
import com.sterlingcommerce.woodstock.ops.server.OpsServerRMIImpl;
import com.sterlingcommerce.woodstock.services.IService;
import com.sterlingcommerce.woodstock.util.frame.Manager;
import com.sterlingcommerce.woodstock.util.frame.jdbc.JDBCService;
import com.sterlingcommerce.woodstock.util.frame.lock.LockEntry;
import com.sterlingcommerce.woodstock.util.frame.lock.LockManager;
import com.sterlingcommerce.woodstock.util.frame.log.LogService;
import com.sterlingcommerce.woodstock.util.frame.log.Logger;
import com.sterlingcommerce.woodstock.util.frame.sequencedlock.FromSequenceInfo;
import com.sterlingcommerce.woodstock.util.frame.sequencedlock.SequenceNode;
import com.sterlingcommerce.woodstock.util.frame.sequencedlock.SequencedLockManager;
import com.sterlingcommerce.woodstock.util.frame.sequencedlock.SequencingException;
import com.sterlingcommerce.woodstock.util.frame.sequencedlock.ToSequenceInfo;
import com.sterlingcommerce.woodstock.workflow.Document;
import com.sterlingcommerce.woodstock.workflow.SystemWorkFlowContext;
import com.sterlingcommerce.woodstock.workflow.WorkFlowContext;
import com.sterlingcommerce.woodstock.workflow.WorkFlowException;
import com.sterlingcommerce.woodstock.workflow.WorkFlowMonitor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;

public class LockService implements IService {
	private static final transient String LOCK_KEY_PARM = "LOCK_KEY";
	private static final transient String ACTION_PARM = "ACTION";
	private static final transient String USER_PARM = "USER";
	private static final transient String TIME_PARM = "DURATION";
	private static final transient String TIMEOUT_PARM = "TIMEOUT";
	private static final transient String WAIT_PARM = "WAIT_TIME";
	private static final transient String SEQUENCE_ID = "SEQUENCE_ID";
	private static final transient String SEQUENCE_TYPE = "SEQUENCE_TYPE";
	private static final transient String SEQUENCE_RULE = "SEQUENCE_RULE";
	private static final transient String IGNORE_SEQUENCE_ERRORS = "IGNORE_SEQUENCE_ERRORS";
	private static final transient String MAX_LOCK_PARM = "MAX_MEM_LOCK_PER_NODE_LISTED";
	private static final transient String CHECK = "check";
	private static final transient String LOCK = "lock";
	private static final transient String UNLOCK = "unlock";
	private static final transient String TOUCH = "touch";
	private static final transient String SET_SEQUENCE = "set_sequence";
	private static final transient String NOTIFY_SEQUENCE = "notify_sequence";
	private static final transient String SEQUENCE_UI = "sequence_ui";
	private static final transient String UPDATE_SEQUENCE = "update_sequence";
	private static final transient String WAIT_SEQUENCE = "wait_sequence";
	private static final transient String CONTINUE_SEQUENCE = "continue_sequence";
	private static final transient String LIST_LOCKS = "list_locks";
	private static String CentralOpsUrl = null;

	private boolean CLEAR_ON_START_UP = false;
	private boolean LOCAL_LOCKING = true;
	private boolean UNLOCK_BY_USER = false;
	private boolean IN_TRANSACTION = false;

	public LockService() {
	}

	public WorkFlowContext processData(WorkFlowContext wfc)
			throws RemoteException, WorkFlowException {
		wfc.harnessRegister();

		SystemWorkFlowContext swfc = null;
		String msg = null;
		StringBuffer buf = new StringBuffer();

		Long time = null;
		Long timeout = null;
		String key = null;
		String user = null;
		String action = null;
		String duration = null;
		String timeoutStr = null;
		String clearonstart = null;
		boolean lockTimeOut = false;

		key = wfc.getParm("LOCK_KEY");
		action = wfc.getParm("ACTION");

		Properties props = Manager.getProperties("workflows");
		clearonstart = props.getProperty("LockService_Clearonstart");
		if ((clearonstart != null)
				&& (clearonstart.trim().equalsIgnoreCase("TRUE"))) {
			this.CLEAR_ON_START_UP = true;
		}

		clearonstart = wfc.getParm("CLEAR_ON_START_UP");
		if ((clearonstart != null)
				&& (clearonstart.trim().equalsIgnoreCase("TRUE"))) {
			this.CLEAR_ON_START_UP = true;
		}

		String locklocal = wfc.getParm("LOCAL_LOCKING");
		if ((locklocal != null) && (locklocal.trim().equalsIgnoreCase("FALSE"))) {
			this.LOCAL_LOCKING = false;
		}

		String unlockByUser = wfc.getParm("UNLOCK_BY_USER");
		if ((unlockByUser != null)
				&& (unlockByUser.trim().equalsIgnoreCase("TRUE"))) {
			this.UNLOCK_BY_USER = true;
		}

		String in_transaction = wfc.getParm("IN_TRANSACTION");
		if ((in_transaction != null)
				&& (in_transaction.trim().equalsIgnoreCase("TRUE"))) {
			this.IN_TRANSACTION = true;
		}

		if (LogService.out.debug) {
			LogService.logDebug("WORKFLOW", "SERVICES", "DEB_LockService1",
					new Object[] { "" + key });

			LogService.logDebug("WORKFLOW", "SERVICES", "DEB_LockService2",
					new Object[] { "" + action });
		}

		if (((key == null) || (key.equals("")))
				&& (!action.equalsIgnoreCase("set_sequence"))
				&& (!action.equalsIgnoreCase("wait_sequence"))
				&& (!action.equalsIgnoreCase("update_sequence"))
				&& (!action.equalsIgnoreCase("notify_sequence"))
				&& (!action.equalsIgnoreCase("list_locks"))) {

			msg = "Parameter ERROR";
			buf.append("No lock key specified");
			wfc.setBasicStatus(1);
			wfc.setAdvancedStatus(msg);
			if (LogService.out.debug) {
				LogService.logDebug("WORKFLOW", "SERVICES", "DEB_LockService6",
						new Object[] { "" + msg });
			}
		} else {
			if ((action == null) || (action.equals(""))) {
				action = "lock";
			}

			if (action.equalsIgnoreCase("check")) {
				user = wfc.getParm("USER");
				if ((user == null) || (user.equals(""))) {
					user = "LockService";
				}
				boolean lockExists = false;
				Vector v = LockManager.list(key, user);
				int i = 0;
				for (int len = v.size(); i < len; i++) {
					String lock = (String) v.elementAt(i);
					if (lock.equals(key)) {
						lockExists = true;
					}
				}
				if (lockExists) {
					wfc.setWFContent("LOCK_EXIST", "true");
				} else {
					wfc.setWFContent("LOCK_EXIST", "false");
				}
				wfc.setBasicStatus(0);
			} else if (action.equalsIgnoreCase("lock")) {
				duration = wfc.getParm("DURATION");
				try {
					time = new Long(duration);
				} catch (NumberFormatException e) {
					msg = "Parameter ERROR for DURATION(" + duration + ")";
					buf.append("Unable to parse Config Parameter");
					wfc.setBasicStatus(1);
					wfc.setWFStatusRpt("Status_Report", msg);
					wfc.setAdvancedStatus(msg);
					if (LogService.out.debug) {
						LogService.logDebug("WORKFLOW", "SERVICES",
								"DEB_LockService7", new Object[] { "" + msg });
					}
				}

				timeoutStr = wfc.getParm("TIMEOUT");
				if ((timeoutStr != null) && (timeoutStr.length() > 0)) {
					try {
						timeout = new Long(timeoutStr);
					} catch (NumberFormatException e) {
						msg = "Parameter ERROR for TIMEOUT(" + timeoutStr + ")";
						buf.append("Unable to parse Config Parameter");
						wfc.setBasicStatus(1);
						wfc.setWFStatusRpt("Status_Report", msg);
						wfc.setAdvancedStatus(msg);
						if (LogService.out.debug) {
							LogService.logDebug("WORKFLOW", "SERVICES",
									"DEB_LockService8",
									new Object[] { "" + msg });
						}
					}
					lockTimeOut = true;
				}

				user = wfc.getParm("USER");
				if ((user == null) || (user.equals(""))) {
					user = "LockService";
				}

				String releaseLock = wfc.getParm("RELEASELOCK_FROMRECOVER");
				if ((releaseLock != null)
						&& (releaseLock.toLowerCase().equals("true"))) {
					user = user + "_"
							+ new Long(wfc.getWorkFlowId()).toString();
					SystemWorkFlowContext.set(wfc, "RELEASELOCK_FROMRECOVER",
							"true");
				}

				if (LogService.out.debug) {
					LogService.logDebug("WORKFLOW", "SERVICES",
							"DEB_LockService3", new Object[] { "" + user });

					LogService.logDebug("WORKFLOW", "SERVICES",
							"DEB_LockService4", new Object[] { "" + time });

					LogService.logDebug("WORKFLOW", "SERVICES", "DEB_timeout",
							new Object[] { "" + timeout });
				}

				if (!LockManager.isLocked(key)) {
					if (lockTimeOut) {
						boolean lock_result = true;
						if (this.IN_TRANSACTION) {
							lock_result = LockManager.lockInTrans(key, user,
									time.longValue(), timeout.longValue(),
									this.LOCAL_LOCKING);
						} else {
							lock_result = LockManager.lock(key, user,
									time.longValue(), timeout.longValue(),
									this.LOCAL_LOCKING);
						}
						if (lock_result) {
							buf.append("Successfully obtained Lock\n");
							buf.append("Lock( " + key + ", " + user + ", "
									+ time.toString() + ", "
									+ timeout.toString() + " )\n");
							wfc.setBasicStatus(0);
						} else {
							msg = "LOCK:request failed";
							wfc.setWFContent("LOCK_REQUEST_FAILED", "true");
							buf.append("Unable obtain lock because lock may exist or could not reach the database\n");
							buf.append("Check (Operation --> Lock Manager) to if the lock exists.\n");
							buf.append("Lock( " + key + ", " + user + ", "
									+ time.toString() + ", "
									+ timeout.toString() + " ): " + msg);
							wfc.setBasicStatus(1);
							wfc.setAdvancedStatus(msg);
							if (LogService.out.debug) {
								LogService.logDebug("WORKFLOW", "SERVICES",
										"DEB_LockService5", new Object[] { ""
												+ buf.toString() });
							}
						}
					} else {
						boolean lock_result = true;
						if (this.IN_TRANSACTION) {
							lock_result = LockManager.lockInTrans(key, user,
									time.longValue(), this.CLEAR_ON_START_UP,
									this.LOCAL_LOCKING);
						} else {
							lock_result = LockManager.lock(key, user,
									time.longValue(), this.CLEAR_ON_START_UP,
									this.LOCAL_LOCKING);
						}
						if (lock_result) {
							buf.append("Successfully obtained Lock\n");
							buf.append("Lock( ");
							buf.append(key);
							buf.append(", ");
							buf.append(user);
							buf.append(", ");
							buf.append(time.toString());
							buf.append(" )\n");
							wfc.setBasicStatus(0);
						} else {
							msg = "LOCK:request failed";
							wfc.setWFContent("LOCK_REQUEST_FAILED", "true");
							buf.append("Unable obtain lock because lock may exist or could not reach the database\n");
							buf.append("Check (Operation --> Lock Manager) to if the lock exists.\n");
							buf.append("Lock( ");
							buf.append(key);
							buf.append(", ");
							buf.append(user);
							buf.append(", ");
							buf.append(time.toString());
							buf.append(" ): ");
							buf.append(msg);
							wfc.setBasicStatus(1);
							wfc.setAdvancedStatus(msg);
							if (LogService.out.debug) {
								LogService.logDebug("WORKFLOW", "SERVICES",
										"DEB_LockService51", new Object[] { ""
												+ buf.toString() });
							}
						}
					}
				} else {
					msg = "LOCK:Lock exists";
					wfc.setWFContent("LOCK_EXIST", "true");
					wfc.setBasicStatus(1);
					buf.append("Lock( " + key + ", " + user + ", "
							+ time.toString() + " ): Lock already exists.");
					wfc.setAdvancedStatus(msg);
					if (LogService.out.debug) {
						LogService.logDebug("WORKFLOW", "SERVICES",
								"DEB_LockService52",
								new Object[] { "" + buf.toString() });
					}
				}
			} else if (action.equalsIgnoreCase("unlock")) {
				user = wfc.getParm("USER");
				if ((user == null) || (user.equals(""))) {
					user = "LockService";
				}

				String tmp = (String) SystemWorkFlowContext.get(wfc,
						"RELEASELOCK_FROMRECOVER");
				if ((tmp != null) && (tmp.equals("true"))) {
					user = user + "_"
							+ new Long(wfc.getWorkFlowId()).toString();
				}

				if (LockManager.isLocked(key)) {
					if (this.UNLOCK_BY_USER) {
						if (LockManager.unlock(key, user, this.IN_TRANSACTION)) {
							buf.append("Successfully released Lock ");
							buf.append("Unlock( " + key + ", " + user + " )\n");
							wfc.setBasicStatus(0);
						} else {
							buf.append("Application was unable to release the lock.  ");
							buf.append("Either no lock exists or the application could not reach the database.\n");
							buf.append("Have the Administrator check to see if the lock is still exists in the database ");
							buf.append("(Operation --> Lock Manager).\n");
							buf.append("Unlock( " + key + ", " + user + " )\n");
							wfc.setWFContent("UNLOCK_REQUEST_FAILED", "true");
							wfc.setAdvancedStatus("UNLOCK:request failed");
							wfc.setBasicStatus(1);
						}
					} else if (LockManager.unlock(key, this.IN_TRANSACTION)) {
						buf.append("Successfully released Lock ");
						buf.append("Unlock( " + key + " )\n");
						wfc.setBasicStatus(0);
					} else {
						buf.append("Application was unable to release the lock.  ");
						buf.append("Either no lock exists or the application could not reach the database.\n");
						buf.append("Have the Administrator check to see if the lock is still exists in the database ");
						buf.append("(Operation --> Lock Manager).\n");
						buf.append("Unlock( " + key + " )\n");
						wfc.setWFContent("UNLOCK_REQUEST_FAILED", "true");
						wfc.setAdvancedStatus("UNLOCK:request failed");
						wfc.setBasicStatus(1);
					}
				} else {
					if ((String) wfc.getWFContent("LOCK_EXIST") != null) {
						buf.append("The lock is held by other Process, cannot remove it from this process.");
						wfc.setAdvancedStatus("LOCK held by Another Process");
						wfc.setBasicStatusSuccess();
					} else {
						buf.append("Request to release: ");
						buf.append("No lock by that name found.\n");
						wfc.setAdvancedStatus("UNLOCK:No Lock");
						wfc.setBasicStatus(1);
					}

					if (LogService.out.debug) {
						LogService.logDebug("WORKFLOW", "SERVICES",
								"DEB_LockService53",
								new Object[] { "" + buf.toString() });
					}
				}
			} else if (action.equalsIgnoreCase("touch")) {
				if (LockManager.isLocked(key)) {
					duration = wfc.getParm("DURATION");
					if ((duration != null) && (duration.length() > 0)) {
						try {
							time = new Long(duration);
						} catch (NumberFormatException e) {
							msg = "Parameter ERROR";
							buf.append("Unable to parse Config Parameter");
							wfc.setBasicStatus(1);
							wfc.setWFStatusRpt("Status_Report", msg);
							wfc.setAdvancedStatus(msg);
							if (LogService.out.debug) {
								LogService.logDebug("WORKFLOW", "SERVICES",
										"DEB_LockService9", new Object[] { ""
												+ msg });
							}
						}

						if (LockManager.touch(key, time.longValue())) {
							buf.append("Successfully touched Lock\n");
							buf.append("Touch( " + key + ", " + time.toString()
									+ " )\n");
							wfc.setBasicStatus(0);
						} else {
							msg = "TOUCH:request failed";
							wfc.setWFContent("TOUCH_REQUEST_FAILED", "true");
							buf.append("Unable touched lock because no lock exists or could not reach the database\n");
							buf.append("Check (Operation --> Lock Manager) to if the lock is still exists.\n");
							buf.append("Touch( " + key + ", " + time.toString()
									+ " ): " + msg);
							wfc.setBasicStatus(1);
							wfc.setAdvancedStatus(msg);
							if (LogService.out.debug) {
								LogService.logDebug("WORKFLOW", "SERVICES",
										"DEB_LockService54", new Object[] { ""
												+ buf.toString() });
							}
						}
					} else if (LockManager.touch(key)) {
						buf.append("Successfully touched Lock\n");
						buf.append("Touch( " + key + " )\n");
						wfc.setBasicStatus(0);
					} else {
						msg = "TOUCH:request failed";
						wfc.setWFContent("TOUCH_REQUEST_FAILED", "true");
						buf.append("Unable touched lock because no lock exists or could not reach the database\n");
						buf.append("Check (Operation --> Lock Manager) to if the lock is still exists.\n");
						buf.append("Touch( " + key + " ): " + msg);
						wfc.setBasicStatus(1);
						wfc.setAdvancedStatus(msg);
						if (LogService.out.debug) {
							LogService.logDebug("WORKFLOW", "SERVICES",
									"DEB_LockService55", new Object[] { ""
											+ buf.toString() });
						}
					}
				} else {
					msg = "TOUCH:Lock not exists";
					wfc.setWFContent("LOCK_NOT_EXIST", "true");
					wfc.setBasicStatus(1);
					buf.append("Touch( " + key + " ): Lock not exists.");
					wfc.setAdvancedStatus(msg);
					if (LogService.out.debug) {
						LogService.logDebug("WORKFLOW", "SERVICES",
								"DEB_LockService56",
								new Object[] { "" + buf.toString() });
					}
				}
			} else if (action.equalsIgnoreCase("wait_sequence")) {
				if (wfc.getParm("SEQUENCE_ID") == null) {
					wfc.setWFContent("SEQUENCE_ID", key);
				}
				try {
					ToSequenceInfo toSequenceInfo = new ToSequenceInfo(wfc);
					FromSequenceInfo fromSequenceInfo = SequencedLockManager
							.waitSequence(toSequenceInfo);

					fromSequenceInfo.process(wfc);
				} catch (SequencingException e) {
					LogService.out.logException("WORKFLOW", "SERVICES",
							"ERR_Exception1", e);
					FromSequenceInfo info = e.getSequenceInfo();
					info.process(wfc);
				}
			} else if (action.equalsIgnoreCase("notify_sequence")) {
				if (wfc.getParm("SEQUENCE_ID") == null) {
					wfc.setWFContent("SEQUENCE_ID", key);
				}
				try {
					ToSequenceInfo toSequenceInfo = new ToSequenceInfo(wfc);
					FromSequenceInfo fromSequenceInfo = SequencedLockManager
							.notifySequence(toSequenceInfo);

					fromSequenceInfo.process(wfc);
				} catch (SequencingException e) {
					LogService.out.logException("WORKFLOW", "SERVICES",
							"ERR_Exception2", e);
					FromSequenceInfo info = e.getSequenceInfo();
					info.process(wfc);
				}
			} else if (action.equalsIgnoreCase("set_sequence")) {
				if (wfc.getParm("SEQUENCE_ID") == null) {
					wfc.setWFContent("SEQUENCE_ID", key);
				}
				try {
					ToSequenceInfo toSequenceInfo = new ToSequenceInfo(wfc);
					FromSequenceInfo fromSequenceInfo = SequencedLockManager
							.setSequence(toSequenceInfo);

					fromSequenceInfo.process(wfc);
				} catch (SequencingException e) {
					LogService.out.logException("WORKFLOW", "SERVICES",
							"ERR_Exception3", e);
					FromSequenceInfo info = e.getSequenceInfo();
					info.process(wfc);
				}
			} else if (action.equalsIgnoreCase("update_sequence")) {
				if (wfc.getParm("SEQUENCE_ID") == null) {
					wfc.setWFContent("SEQUENCE_ID", key);
				}
				try {
					ToSequenceInfo toSequenceInfo = new ToSequenceInfo(wfc);
					FromSequenceInfo fromSequenceInfo = SequencedLockManager
							.updateSequence(toSequenceInfo);

					fromSequenceInfo.process(wfc);
				} catch (SequencingException e) {
					LogService.out.logException("WORKFLOW", "SERVICES",
							"ERR_Exception31", e);
					FromSequenceInfo info = e.getSequenceInfo();
					info.process(wfc);
				}
			} else if (action.equalsIgnoreCase("continue_sequence")) {
				FromSequenceInfo fromSequenceInfo = SequencedLockManager
						.continueStuckProcesses(wfc);

				fromSequenceInfo.process(wfc);

			} else if (action.equalsIgnoreCase("sequence_ui")) {

				String resources = wfc.getParm("SEQUENCE_RESOURCE");
				String types = wfc.getParm("SEQUENCE_TYPE");
				String rules = wfc.getParm("SEQUENCE_RULE");

				boolean notify = false;
				String notifyStr = wfc.getParm("NOTIFY_SEQUENCE_SEARCH");
				if ((notifyStr != null) && (notifyStr.equalsIgnoreCase("TRUE"))) {
					notify = true;
				}

				ArrayList resourcesList = null;
				ArrayList typesList = null;
				ArrayList rulesList = null;

				ArrayList items = null;

				Document doc = wfc.newDocument();
				OutputStream docOut = null;
				PrintStream writer = null;
				try {
					docOut = doc.getOutputStream();
					writer = new PrintStream(docOut);

					String sequenceAction = wfc.getParm("SEQUENCE_UI_ACTION");

					if (sequenceAction.equalsIgnoreCase("show_resources")) {
						writer.print("Sequenced Resources: \n");
						items = SequencedLockManager.getResources();

					} else if (sequenceAction.equalsIgnoreCase("show_types")) {
						if (resources != null) {
							resourcesList = new ArrayList();
							resourcesList.add(resources);
							writer.print("Types for resource '");
							writer.print(resources);
							writer.print("':\n");
						} else {
							writer.print("Types for all resources:\n");
						}
						items = SequencedLockManager.getTypes(resourcesList);

					} else if (sequenceAction.equalsIgnoreCase("show_rules")) {
						writer.print("Rules for ");

						if (resources != null) {
							resourcesList = new ArrayList();
							resourcesList.add(resources);
							writer.print("resource '");
							writer.print(resources);
							writer.print("'");

							if (types != null) {
								writer.print(", type '");
								writer.print(resources);
								writer.print("'");
								typesList = new ArrayList();
								typesList.add(types);
							}
						} else {
							writer.print("all resources");
						}
						writer.print(":\n");

						items = SequencedLockManager.getRules(resourcesList,
								typesList);

					} else if (sequenceAction.equalsIgnoreCase("search")) {

						writer.print("Items for ");
						if (resources != null) {
							resourcesList = new ArrayList();
							resourcesList.add(resources);
							writer.print("resource '");
							writer.print(resources);
							writer.print("'");

							if (types != null) {
								writer.print(", type '");
								writer.print(types);
								writer.print("'");
								typesList = new ArrayList();
								typesList.add(types);

								if (rules != null) {
									writer.print(", rule '");
									writer.print(rules);
									writer.print("'");
									rulesList = new ArrayList();
									rulesList.add(rules);
								}
							}
						} else {
							writer.print("all resources");
						}

						items = SequencedLockManager.getSetCopy(resourcesList,
								typesList, rulesList);

						String sequenceState = wfc.getParm("SEQUENCE_STATE");
						if (sequenceState != null) {
							sequenceState = sequenceState.trim();

							Integer state = null;
							if (sequenceState.equalsIgnoreCase("ACTIVE")) {
								state = new Integer(0);
							} else if (sequenceState
									.equalsIgnoreCase("EXPIRED")) {
								state = new Integer(1);
							} else if (sequenceState.equalsIgnoreCase("READY")) {
								state = new Integer(2);
							} else {
								buf.append("Incorrect Sequence State: ");
								buf.append(sequenceState);
								wfc.setBasicStatus(1);
							}

							if (state != null) {
								ArrayList stateList = new ArrayList();
								stateList.add(state);
								items = SequencedLockManager
										.getBySequenceStates(stateList, items);
							}
						}

						String bpState = wfc.getParm("BP_STATE");
						if (bpState != null) {
							Integer state = null;
							if (bpState.equalsIgnoreCase("ACTIVE")) {
								state = new Integer(0);
							} else if (bpState.equalsIgnoreCase("WAITING")) {
								state = new Integer(3);
							} else if (bpState.equalsIgnoreCase("HALTED")) {
								state = new Integer(4);
							} else if (bpState.equalsIgnoreCase("HALTING")) {
								state = new Integer(5);
							} else if (bpState
									.equalsIgnoreCase("INTERRUPTED_AUTO")) {
								state = new Integer(6);
							} else if (bpState
									.equalsIgnoreCase("INTERRUPTED_MAN")) {
								state = new Integer(7);
							} else if (bpState.equalsIgnoreCase("COMPLETE")) {
								state = new Integer(1);
							} else {
								buf.append("Incorrect BP State: ");
								buf.append(sequenceState);
								wfc.setBasicStatus(1);
							}

							ArrayList statuses = new ArrayList();

							if (state != null) {
								ArrayList stateList = new ArrayList();
								stateList.add(state);
								try {
									items = SequencedLockManager
											.getByWorkFlowStatesAndStatuses(
													stateList, statuses, items);

								} catch (SQLException e) {

									LogService.out.logException("WORKFLOW",
											"SERVICES", "ERR_LockService", e);
									items = null;
									buf.append("Error finding sequences by BP state");
									wfc.setBasicStatus(1);
								}
							} else {
								items = null;
							}
						}
					}

					writer.print(":\n\n");

					if ((items != null) && (!items.isEmpty())) {
						Iterator iter = items.iterator();

						WorkFlowMonitor wfm = new WorkFlowMonitor();

						Connection conn = null;
						try {
							conn = JDBCService
									.getConnection(SequencedLockManager.localPool);

							while (iter.hasNext()) {
								Object obj = iter.next();
								if ((obj instanceof String)) {
									writer.print(obj);

								} else if ((obj instanceof SequenceNode)) {
									SequenceNode node = (SequenceNode) obj;
									writer.print("    SequenceId: ");
									writer.print(node.id);
									writer.print("\n");
									writer.print("    Resource Name: ");
									writer.print(node.resource);
									writer.print("\n");
									writer.print("    Resource Type: ");
									writer.print(node.type);
									writer.print("\n");
									writer.print("    Resource Rule: ");
									writer.print(node.rule);
									writer.print("\n");
									writer.print("    WorkFlowId: ");
									writer.print(node.workFlowId);
									writer.print("\n");
									writer.print("    Sequence State: ");
									int sequenceState = node.state;
									switch (sequenceState) {
									case 0:
										writer.print("Active");
										break;
									case 1:
										writer.print("Expired");
										break;
									case 2:
										writer.print("Ready");
										break;
									case 3:
										writer.print("Complete");
									}

									writer.print("\n");
									writer.print("    WorkFlow State: ");
									int wfState = wfm.getState(
											Long.toString(node.workFlowId),
											conn);

									switch (wfState) {
									case 0:
										writer.print("Active");
										break;
									case 5:
										writer.print("Halting");
										break;
									case 3:
										writer.print("Waiting");
										break;
									case 2:
										writer.print("Terminated");
										break;
									case 7:
										writer.print("Interrupted Manual");
										break;
									case 6:
										writer.print("Interrupted Auto");
										break;
									case 4:
										writer.print("Halted");
										break;
									case 1:
										writer.print("Complete");
										break;
									case 8:
										writer.print("Waiting");
										break;
									default:
										writer.print("Unknown");
									}

									if (notify) {
										ToSequenceInfo toInfo = new ToSequenceInfo();
										toInfo.setId(node.id);
										FromSequenceInfo fromInfo = SequencedLockManager
												.notifySequence(toInfo);

										if (fromInfo.isError()) {
											LogService.out
													.logError(
															"WORKFLOW",
															"SERVICES",
															"ERR_fromInfo_getMessage",
															new Object[] { ""
																	+ fromInfo
																			.getMessage() });
										}
									}
								}

								writer.print("\n\n");
							}

							wfc.setBasicStatus(0);
						} catch (SQLException e) {
							buf.append("Caught Exception: ");
							ByteArrayOutputStream bout = new ByteArrayOutputStream();
							PrintStream exceptionWriter = new PrintStream(bout);

							exceptionWriter.flush();
							buf.append(bout.toString());

							LogService.out.logException("WORKFLOW", "SERVICES",
									"ERR_SQLException", e);
							wfc.setBasicStatus(1);
						} finally {
							if (conn != null) {
								JDBCService.freeConnection(
										SequencedLockManager.localPool, conn);
							}
						}
					} else {
						wfc.setBasicStatus(0);
						buf.append("   Nothing to display");
					}
				} catch (Exception e) {
					ByteArrayOutputStream bout;
					PrintStream exceptionWriter;
					buf.append("Caught Exception");
					bout = new ByteArrayOutputStream();
					exceptionWriter = new PrintStream(bout);

					exceptionWriter.flush();
					buf.append(bout.toString());

					LogService.out.logException("WORKFLOW", "SERVICES",
							"ERR_Exception4", e);
					wfc.setBasicStatus(1);
				} finally {
					ByteArrayOutputStream bout;
					PrintStream exceptionWriter;
					if (writer != null) {
						writer.flush();
					}
					if (docOut != null) {
						try {
							docOut.close();
						} catch (IOException e) {
							buf.append("Could not close document output");
							bout = new ByteArrayOutputStream();
							exceptionWriter = new PrintStream(bout);

							exceptionWriter.flush();
							buf.append(bout.toString());

							LogService.out.logException("WORKFLOW", "SERVICES",
									"ERR_document", e);
							wfc.setBasicStatus(1);
						}
					}

					if (doc != null) {
						wfc.setDocument(doc);

					}

				}

			} else if (action.equalsIgnoreCase("list_locks")) {
				boolean error = false;
				Long maxPerNode = null;
				String maxPerNodeStr = wfc
						.getParm("MAX_MEM_LOCK_PER_NODE_LISTED");
				if ((maxPerNodeStr != null)
						&& (maxPerNodeStr.trim().length() > 0)) {
					try {
						maxPerNode = new Long(maxPerNodeStr.trim());
					} catch (NumberFormatException e) {
						msg = "Parameter ERROR";
						buf.append("Unable to parse Config Parameter");
						wfc.setBasicStatus(1);
						wfc.setWFStatusRpt("Status_Report", msg);
						wfc.setAdvancedStatus(msg);
						error = true;
						if (LogService.out.debug) {
							LogService.logDebug("WORKFLOW", "SERVICES",
									"DEB_LockService9",
									new Object[] { "" + msg });
						}
					}
				}
				if (!error) {
					Document doc = wfc.newDocument();
					OutputStream docOut = null;
					PrintStream writer = null;
					String opsUrl = null;
					try {
						docOut = doc.getOutputStream();
						writer = new PrintStream(docOut);
						writer.println("<Locks>");
						writer.println("<DBLocks>");
						Vector dbLocks = LockManager.listLocks(null, null);
						for (Iterator iter = dbLocks.iterator(); iter.hasNext();) {
							LockEntry le = (LockEntry) iter.next();
							writer.println(le.getXML());
						}
						writer.println("</DBLocks>");
						Hashtable parms = new Hashtable();
						if ((maxPerNode != null)
								&& (maxPerNodeStr.trim().length() > 0)) {
							parms.put("max_locks", maxPerNodeStr);
						}
						opsUrl = CentralOpsUrl;
						if (opsUrl == null) {
							opsUrl = getOpsUrl();
						}
						if (opsUrl == null) {
							throw new Exception("Failed to get OPS URL!");
						}
						String memLocks = SendOpsCommand.sendCommand(opsUrl,
								"LISTINMEMLOCKS", null, false, parms, false,
								true);
						if (memLocks != null) {
							if ((memLocks.startsWith("Internal failure"))
									|| (memLocks.startsWith("Error:"))) {
								throw new Exception(
										"Failed to get in memory lock details from Ops.  Ops returned:"
												+ memLocks);
							}
							writer.print(memLocks);
						}
						writer.println("</Locks>");
						wfc.setBasicStatus(0);
					} catch (Exception e) {
						ByteArrayOutputStream bout;
						PrintStream exceptionWriter;
						if (opsUrl != null) {
							clearOpsUrl();
						}
						buf.append("Caught Exception");
						bout = new ByteArrayOutputStream();
						exceptionWriter = new PrintStream(bout);

						exceptionWriter.flush();
						buf.append(bout.toString());
						LogService.out.logException("WORKFLOW", "SERVICES",
								"ERR_LockService_Exception5", e);
						wfc.setBasicStatus(1);
					} finally {
						ByteArrayOutputStream bout;
						PrintStream exceptionWriter;
						if (writer != null) {
							writer.flush();
						}
						if (docOut != null) {
							try {
								docOut.close();
							} catch (IOException e) {
								buf.append("Could not close document output");
								bout = new ByteArrayOutputStream();
								exceptionWriter = new PrintStream(bout);

								exceptionWriter.flush();
								buf.append(bout.toString());
								LogService.out.logException("WORKFLOW",
										"SERVICES", "ERR_document", e);
								wfc.setBasicStatus(1);
							}
						}

						if (doc != null) {
							wfc.putPrimaryDocument(doc);
						}
					}
				}
			} else {
				buf.append("Unknown ");
				buf.append("ACTION");
				buf.append(action);
				buf.append(" specified");
				wfc.setBasicStatus(1);
				wfc.setAdvancedStatus("Unknow ACTION");
				if (LogService.out.debug) {
					LogService.logDebug("WORKFLOW", "SERVICES",
							"DEB_LockService10", new Object[] { "" + msg });
				}
			}
		}

		wfc.setWFStatusRpt("Status_Report", buf.toString());
		wfc.unregisterThread();

		return wfc;
	}

	private static synchronized String getOpsUrl() {
		OpsServerRMIImpl ops = OpsServerRMIImpl.getInstance();
		if (ops != null) {
			CentralOpsUrl = ops.getCentralOpsURL();
			if ((CentralOpsUrl != null) && (!CentralOpsUrl.endsWith("/")))
				CentralOpsUrl += "/";
			return CentralOpsUrl;
		}
		return null;
	}

	private static synchronized void clearOpsUrl() {
		CentralOpsUrl = null;
	}
}
