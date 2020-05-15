<!DOCTYPE HTML PUBLIC "-//IETF//DTD HTML//EN">
<HTML>
<HEAD>
<meta http-equiv="charset" content="UTF-8">
<%@ page import="com.sterlingcommerce.woodstock.ui.BaseUIGlobals"%>
<%@ page import="java.util.ArrayList"%>
<%@ page import="com.sterlingcommerce.woodstock.util.frame.jdbc.Conn"%>
<%@ page import="java.util.Hashtable"%>
<%@ page import="java.sql.*"%>
<%@ page import="com.sterlingcommerce.woodstock.util.frame.jdbc.JDBCService"%>
<%@ page import="com.sterlingcommerce.woodstock.util.frame.jdbc.JDBCService"%>

<title>usage</title>
<%
	final int UNKNOWN = -1;
	final int ACTIVE = 0;
	final int COMPLETE = 1;
	final int TERMINATED = 2;
	final int WAITING = 3;
	final int HALTED = 4;
	final int HALTING = 5;

	final int INTERRUPTED_AUTO = 6;
	final int INTERRUPTED_MAN = 7;

	final int ACTIVE_WAITING = 8;
	final int FORCE_TERMINATED = 9;
	final int WAITING_ON_IO = 10;
	final int COMPLETE_OR_TERMINATED = 20;
	final int INDEXED_FAILURES = -5;

	final int PRECEDENCE_ACTIVE = 0;
	final int PRECEDENCE_HALTING = 1;
	final int PRECEDENCE_WAITING = 2;
	final int PRECEDENCE_WAITING_ON_IO = 3;
	final int PRECEDENCE_TERMINATED = 4;
	final int PRECEDENCE_INTERRUPTED_MAN = 5;
	final int PRECEDENCE_INTERRUPTED_AUTO = 6;
	final int PRECEDENCE_HALTED = 7;
	final int PRECEDENCE_COMPLETE = 8;
	final int PRECEDENCE_ACTIVE_WAITING = 9;

	StringBuffer s = new StringBuffer();

	ArrayList wfarray = BaseUIGlobals.getActualWorkFlowUsage();
	
	if (wfarray != null) {
		for (int i = 0; i < wfarray.size(); i++) {
			Hashtable entry = (Hashtable) wfarray.get(i);
			int state = ((Integer) entry.get("STATE")).intValue();
			ArrayList al = (ArrayList) entry.get("IDS");

			int numbps = al.size();

			switch (state) {
			case 0:
				s.append("Active");
				out.println("Active BP Count: " + al.size() + "<br>");
				break;
			case 5:
				s.append("Halting");
				out.println("Halting BP Count: " + al.size() + "<br>");
				break;
			case 4:
				s.append("Halt");
				out.println("Halt BP Count: " + al.size() + "<br>");
				break;
			case 3:
				s.append("Waiting");
				out.println("Waiting BP Count: " + al.size() + "<br>");
				break;
			case 7:
				s.append("Int_man");
				out.println("InterruptedMan BP Count: " + al.size() + "<br>");
				break;
			case 6:
				s.append("Int");
				out.println("InterruptedAuto BP Count: " + al.size() + "<br>");
				break;
			case 10:
				s.append("WaitingOnIO");
				out.println("WaitingOnIO BP Count: " + al.size() + "<br>");
				break;
			case 11:
				s.append("AsyncQueued");
				out.println("AsyncQueued BP Count: " + al.size() + "<br>");
				break;
			case 12:
				s.append("Softstop");
				out.println("SoftStop BP Count: " + al.size() + "<br>");
				break;
			}

		}
	}

	

	     ArrayList activeList = new ArrayList();
	     ArrayList haltingList = new ArrayList();
	     ArrayList haltedList = new ArrayList();
	     ArrayList waitingList = new ArrayList();
	     ArrayList waitingOnIOList = new ArrayList();
	     ArrayList interruptedAutoList = new ArrayList();
	     ArrayList interruptedManList = new ArrayList();
	     ArrayList asyncQueuedList = new ArrayList();
	     ArrayList haltedSoftstopList = new ArrayList();
	     ArrayList idList = new ArrayList();
	Connection conn = null;

	ResultSet rs = null;
	PreparedStatement pstmt = null;
	StringBuffer sb = new StringBuffer();
	String sql = null;
	boolean passedIn = false;
	try {
		conn = Conn.getConnection();

		sb.append(" SELECT ");
		sb.append(JDBCService.getNamedSQL(conn, "getStateAndStatus_MAIN"));
		sb.append(" ");
		sb.append(JDBCService.getNamedSQL(conn, "wfm_getAllIdsWithStateNew_where"));
		sql = sb.toString();

		pstmt = conn.prepareStatement(sql);
		
		out.println("SQL Query : " + sql + "<br>");

		rs = pstmt.executeQuery();

		int numFound = 0;

		while (rs != null && rs.next()) {
			String wfId = rs.getString("WORKFLOW_ID");
			int state = rs.getInt("STATE");

			out.println("WFID : " + wfId + " State: " + state + "<br>");
			
			int ret = -1; //UNKNOWN
			     switch (state) {
			     case 1: 
			       ret = 0; //ACTIVE
			       break;
			     case 9: 
			       ret = 1; //COMPLETE
			       break;
			     case 5: 
			       ret = 2; //TERMINATED
			       break;
			     case 3: 
			       ret = 3; //WAITING
			       break;
			     case 0: 
			       ret = 11; //AsynQueued
			       break;
			     case -1: 
			       ret = 12; //HaltedSoftStop
			       break;
			     case 4: 
			       ret = 10; //WAITINGONIO
			       break;
			     case 8: 
			       ret = 4; //HALTED
			       break;
			     case 2: 
			       ret = 5; //HALTING
			       break;
			     case 7: 
			       ret = 6; //InterruptedAuto
			       break;
			     case 6: 
			       ret = 7; //InterruptedMan
			       break;			     
			     case 10: 
			       ret = 0; //ACTIVE
			       break;
			     }

			     switch (ret) {
			              case 0:  activeList.add(wfId); break;  //ACTIVE
			              case 5:  haltingList.add(wfId); break; //HALTING
			              case 4:  haltedList.add(wfId); break;  //HALTED
			              case 3:  waitingList.add(wfId); break; //WAITING
			              case 10:  waitingOnIOList.add(wfId); break; //WAITINGONIO
			              case 6:  interruptedAutoList.add(wfId); break; //InterruptedAuto
			              case 7:  interruptedManList.add(wfId); break; //InterruptedMan
			              case 11:  asyncQueuedList.add(wfId); break; //AsynQueued
			              case 12:  haltedSoftstopList.add(wfId); break; //HaltedSoftStop
			    }

		}

		Hashtable active = new Hashtable();
		active.put("STATE", new Integer(ACTIVE));
		active.put("IDS", activeList);
		idList.add(active);
		
		out.println("Active BP Count: " + activeList.size() + "<br>");

		Hashtable halted = new Hashtable();
		halted.put("STATE", new Integer(HALTED));
		halted.put("IDS", haltedList);
		idList.add(halted);
		
		out.println("Halted BP Count: " + haltedList.size() + "<br>");
		
		Hashtable halting = new Hashtable();
		halting.put("STATE", new Integer(HALTING));
		halting.put("IDS", haltingList);
		idList.add(halting);

		out.println("Halting BP Count: " + haltingList.size() + "<br>");
		
		Hashtable waiting = new Hashtable();
		waiting.put("STATE", new Integer(WAITING));
		waiting.put("IDS", waitingList);
		idList.add(waiting);
		
		out.println("Waiting BP Count: " + waitingList.size() + "<br>");

		Hashtable waitingOnIO = new Hashtable();
		waitingOnIO.put("STATE", new Integer(WAITING_ON_IO));
		waitingOnIO.put("IDS", waitingOnIOList);
		idList.add(waitingOnIO);
		
		out.println("WaitingOnIO BP Count: " + waitingOnIOList.size() + "<br>");

		Hashtable interrupted = new Hashtable();
		interrupted.put("STATE", new Integer(INTERRUPTED_MAN));
		interrupted.put("IDS", interruptedManList);
		idList.add(interrupted);
		
		out.println("InterruptedMan BP Count: " + interruptedManList.size() + "<br>");

		Hashtable interruptedAuto = new Hashtable();
		interruptedAuto.put("STATE", new Integer(INTERRUPTED_AUTO));
		interruptedAuto.put("IDS", interruptedAutoList);
		idList.add(interruptedAuto);
		
		out.println("InterruptedAuto BP Count: " + interruptedAutoList.size() + "<br>");
		
		

	} catch (SQLException sqe) {
		sqe.printStackTrace();
	} catch (Exception e) {
		e.printStackTrace();
	} finally {
		try {
			if (rs != null) {
				rs.close();
			}
			if (pstmt != null) {
				pstmt.close();
			}
		} catch (SQLException sqe) {
			sqe.printStackTrace();
		}

		if (conn != null && !passedIn) {
			Conn.freeConnection(conn);
		}

	}
%>

</head>
<body>

</body>
</html>