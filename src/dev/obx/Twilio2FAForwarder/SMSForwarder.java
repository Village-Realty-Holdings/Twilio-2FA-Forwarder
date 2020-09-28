package dev.obx.Twilio2FAForwarder;

import java.io.File;
import java.io.IOException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.*;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.twilio.Twilio;
import com.twilio.twiml.MessagingResponse;
import com.twilio.twiml.MessagingResponse.Builder;
import com.twilio.twiml.TwiMLException;
import dev.obx.Twilio2FAForwarder.Config.TwoFAPhoneNumber;
import dev.obx.Twilio2FAForwarder.Config.TwilioConfig;

/**
 * Servlet implementation class SMSForwarder
 */
@WebServlet("/sms")
public class SMSForwarder extends HttpServlet {
    private static final long serialVersionUID = 1L;

    // Logger
    protected static Logger logger = Logger.getLogger("dev.obx.SMSForwarder");

    // ExecutorService
    private ExecutorService executor;

    // DataSource
    private DataSource dataSource = null;

    // Twilio Configuration
    private TwilioConfig twilioConfig;

    /**
     * @see Servlet#init(ServletConfig)
     */
    public void init(ServletConfig config) throws ServletException {
        String configFileLocation = "";

        setupExecutorPool();
        try {
            Context ctx = new InitialContext();
            configFileLocation = (String) ctx.lookup("java:/comp/env/ConfigLocation");
            dataSource = (DataSource) ctx.lookup("java:/comp/env/jdbc/2FADatasource");

        } catch (NamingException ne) {
            logger.severe("Exception getting JNDI resources.");
            logger.log(Level.SEVERE, ne.getMessage(), ne);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        try {
            twilioConfig = mapper.readValue(new File(configFileLocation), TwilioConfig.class);
        } catch (IOException e) {
            logger.severe(e.getMessage());
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    /**
     * @see Servlet#destroy()
     */
    public void destroy() {
        executor.shutdown();
    }

    /**
     * @see HttpServlet#service(HttpServletRequest request, HttpServletResponse
     *      response)
     */
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        PrintWriter out = response.getWriter();
        //String messageSid; // MessageSid (might use in future for logging or delivery checks.
        String accountSid; // AccountSid
        String to; // To
        String from; // From
        String body; // Body

        accountSid = coalesce(request.getParameter("AccountSid"));
        //messageSid = coalesce(request.getParameter("MessageSid"));
        to = coalesce(request.getParameter("To"));
        from = coalesce(request.getParameter("From"));
        body = coalesce(request.getParameter("Body"));
        
        // Make sure the incoming transaction has the same AccountSid that we are configured with.
        if(!accountSid.equals(twilioConfig.twilioAccount.accountSid)) {
            response.sendError(500, "Invalid Account SID");
            return;
        }
        
        TwoFAPhoneNumber predicate = new TwoFAPhoneNumber();
        predicate.phoneNumber = to;
        if(!twilioConfig.phoneNumbers.stream().anyMatch(predicate)) {
            response.sendError(500, "No such configuration");
            return;
        }
        
        int numRecipients = -1;
        List<TwoFAPhoneNumber> pn = twilioConfig.phoneNumbers.stream().filter(predicate).collect(Collectors.<TwoFAPhoneNumber>toList());
        try (Connection con = dataSource.getConnection()){
            try (PreparedStatement stmt = con.prepareStatement("SELECT COUNT(*) FROM users WHERE id IN (SELECT user_id FROM user_group where group_id = ?)")) {
                stmt.setInt(1, pn.get(0).groupId);
                ResultSet rs = stmt.executeQuery();
                if(rs.next()) {
                    numRecipients = rs.getInt(1);
                }
            }            
        } catch (SQLException sqle) {
           logger.severe(sqle.getMessage());
           logger.log(Level.SEVERE, sqle.getMessage(), sqle);
           response.sendError(500, "Internal Error");
           return;
        }
        
        String message = "From: " + from + "\nMessage:\n" + body;
        if(numRecipients <= twilioConfig.twimlLimit) {
            sendViaTwiml(response, out, pn.get(0), to, from, message);
        } else {
            sendViaExecutor(response, out, pn.get(0), to, from, message);
        }
        
    }
    
    private void sendViaTwiml(HttpServletResponse response, PrintWriter out, TwoFAPhoneNumber pn, String to, String from, String message) throws IOException {
        
        ArrayList<String> mobiles = new ArrayList<String>();
        try (Connection con = dataSource.getConnection()){
            try (PreparedStatement stmt = con.prepareStatement("SELECT mobile FROM users WHERE id IN (SELECT user_id FROM user_group where group_id = ?)")) {
                stmt.setInt(1, pn.groupId);
                ResultSet rs = stmt.executeQuery();
                while(rs.next()) {
                    mobiles.add(rs.getString(1));
                }
            }
        } catch (SQLException sqle) {
           logger.severe(sqle.getMessage());
           logger.log(Level.SEVERE, sqle.getMessage(), sqle);
           response.sendError(500, "Internal Error");
           return;
        }
        
        Builder mBuilder = new MessagingResponse.Builder();
        
        
        for(String mobile: mobiles) {
            mBuilder = mBuilder.message(new com.twilio.twiml.messaging.Message.Builder(message).to(mobile).from(from).build());
        }
        
        try {
            out.append(mBuilder.build().toXml());
        } catch (TwiMLException e) {
            logger.severe(e.getMessage());
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
        
    }
    
    private void sendViaExecutor(HttpServletResponse response, PrintWriter out, TwoFAPhoneNumber pn, String to, String from, String message) {
        
        // Send a blank response document.
        Builder mBuilder = new MessagingResponse.Builder();
        try {
            out.append(mBuilder.build().toXml());
        } catch (TwiMLException e) {
            logger.severe(e.getMessage());
            logger.log(Level.SEVERE, e.getMessage(), e);
        }
        
        MessageThread task = new MessageThread(dataSource, twilioConfig, from, message, pn);
        executor.submit(task);
    }

    private String coalesce(String maybeNull) {
        return (maybeNull != null ? maybeNull : "");
    }

    private void setupExecutorPool() {
        int numThreads;

        try {
            Context ctx = new InitialContext();
            numThreads = ((Integer) ctx.lookup("java:/comp/env/ThreadPoolSize")).intValue();

        } catch (NamingException ne) {
            logger.severe("Exception getting ThreadPoolSize, proceeding with default value of 5.");
            logger.log(Level.SEVERE, ne.getMessage(), ne);
            numThreads = 5;
        }

        executor = Executors.newFixedThreadPool(numThreads);
    }
    
class MessageThread implements Runnable {
        
        private DataSource dataSource;
        private TwilioConfig twilioConfig;
        private String from;
        private String message;
        private TwoFAPhoneNumber pn;
        
        public MessageThread(DataSource dataSource, TwilioConfig twilioConfig, String from, String message, TwoFAPhoneNumber pn) {
            this.dataSource = dataSource;
            this.twilioConfig = twilioConfig;
            this.from = from;
            this.message = message;
            this.pn = pn;
        }
        
        public void run() {
            
            SMSForwarder.logger.log(Level.INFO, "New 2FA message on executor from: " + from);
            
            Twilio.init(twilioConfig.twilioAccount.accountSid, twilioConfig.twilioAccount.authToken);
            
            try (Connection con=dataSource.getConnection()) {
                try (PreparedStatement st=con.prepareStatement("SELECT mobile FROM users WHERE id IN (SELECT user_id FROM user_group where group_id = ?)")) {
                    st.setInt(1, pn.groupId);
                    ResultSet rs = st.executeQuery();
                    while(rs.next()) {
                        String mobile = rs.getString("mobile");
                        com.twilio.rest.api.v2010.account.Message.creator(
                                new com.twilio.type.PhoneNumber(mobile),
                                new com.twilio.type.PhoneNumber(pn.phoneNumber),
                                message).create();
                    }
                }            
            } catch(Exception e) {
                if(e.getCause() != null) { e = (Exception) e.getCause(); }
                logger.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }
}
