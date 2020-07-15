package org.demo;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;


public class GCalendar {

    private static final String APPLICATION_NAME = "Google Calendar Information";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    
    /**
     * Global instance of the scopes required by this quickstart.
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "./credentials.json";
    private static final String CALENDARIDS_FILE_PATH = "./calendar-ids.txt";
    private static final String EXCLUDE_EVENTS_FILE_PATH = "./exclude-events.txt";

    
    public static void main(String... args) throws IOException, GeneralSecurityException {
    	
    	if (args.length == 0) {
            throw new FileNotFoundException("usage: start and end dates required in format yyyy-mm-dd");
        }
    	
        // Build a new authorized API client service.
    	final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
    	
        Calendar service = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();
        
        String startDate = args[0];
        String endDate = args[1];
        
        DateTime timeMin = parseDate("startDate", startDate);
        DateTime timeMax = parseDate("endDate", endDate);
        
        List<String> calendarIds = readFile(CALENDARIDS_FILE_PATH);
        
        if (calendarIds.size() == 0) {
            throw new FileNotFoundException("Calendar Ids file not found or is empty: " + CALENDARIDS_FILE_PATH);
        }
        
        String outputFile = startDate + "_" + endDate + ".csv";
        
        File f = new File(outputFile);
        BufferedWriter bw = null;
        		
        if (f.createNewFile()) {
        	FileOutputStream fos = new FileOutputStream(f);
        	bw = new BufferedWriter(new OutputStreamWriter(fos));
        	bw.write("Id,Event,DateTime\n");
        }
        else {
        	throw new IOException("File already exists: " + f.getName());
        }
        
        for (String calendarId : calendarIds) {
			listCalendarEvents(service, calendarId, timeMin, timeMax, bw);
		}
        
        bw.close();
        
        System.out.println("\nOutputfile: " + outputFile);
		
        //listCalendars(service);
    }
    
    
    public static void listCalendarEvents(Calendar service, String calendarId, DateTime timeMin, DateTime timeMax, BufferedWriter bw) throws IOException {
	    
		Events events = service.events().list(calendarId)
                .setTimeMin(timeMin)
                .setTimeMax(timeMax)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();
        
        List<Event> items = events.getItems();
        
        if (items.isEmpty()) {
            System.out.println("No upcoming events found.");
        } 
        else {
            
        	int i = 0;
        	
            for (Event event : items) {
            	
            	String eventSummary = event.getSummary();
            	
            	if (writeEvent(eventSummary.toLowerCase())) {
            		DateTime start = event.getStart().getDateTime();
                    
                    if (start == null) {
                        start = event.getStart().getDate();
                    }
                    
                    String str = calendarId + "," + event.getSummary() + "," + start;
                    bw.write(str);
            		bw.newLine();
            		
            		i++;
            	}
                
            }
            
            System.out.println(calendarId + "," + i);
        }
	}
 
    
    private static boolean writeEvent(String eventSummary) throws IOException {
    	
    	if (eventSummary.contains("scrum") ||
    		eventSummary.contains("bi-weekly") ||
    		eventSummary.contains("biweekly") ||
    		eventSummary.contains("battle buddies") ||
    		eventSummary.contains("pm/em/se") ||
    		eventSummary.contains("talkto") ||
    		eventSummary.contains("weekly") ||
    		eventSummary.contains("international team") ||
    		eventSummary.contains("1:1") ||
    		eventSummary.contains("lunch") ||
     		eventSummary.contains("out of office")) {
    		return false;
    	}
    	else {
    		
    		List<String> excludeEvents = readFile(EXCLUDE_EVENTS_FILE_PATH);
    		
    		if (excludeEvents.size() > 0) {
    			
    			boolean status = true;
    			
    			for (String event : excludeEvents) {
    				
    				String eventStr = event.toLowerCase();
    				
    				if (!isBlankString(eventStr) && eventSummary.contains(eventStr)) {
    					status = false;
    				}
    			}
    			
    			return status;
    		}
    		else {
    			return true;
    		}
    	}
	}


    private static boolean isBlankString(String string) {
        return string == null || string.trim().isEmpty();
    }
    
	private static DateTime parseDate(String period, String periodStr) {
    	
    	String periodTime;
    	
    	if (period == "startDate")
    		periodTime = "00:00:00";
    	else
    		periodTime = "23:00:00";
    			
		String[] periodDate = periodStr.split("-");
 		
        DateTime periodDateDt = new DateTime(getDateMs(periodDate[0], periodDate[1], periodDate[2], periodTime));
        
        return periodDateDt;
	}


	/**
     * Creates an authorized Credential object.
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
    	
        // Load client secrets.        
        InputStream in = new FileInputStream(CREDENTIALS_FILE_PATH);
        
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    
	public static void listCalendars(Calendar service) throws IOException {
		String pageToken = null;
		
		do {
			CalendarList calendarList = service.calendarList().list().setPageToken(pageToken).execute();
			List<CalendarListEntry> items = calendarList.getItems();

			for (CalendarListEntry calendarListEntry : items) {
				System.out.println(calendarListEntry.getSummary());
			}
			
			pageToken = calendarList.getNextPageToken();
		} 
		while (pageToken != null);
	}
	
	
	public static long getDateMs(String yr, String mth, String day, String time) {
		String myDate = yr + "/" + mth + "/" + day + " " + time;
		
		LocalDateTime localDateTime = LocalDateTime.parse(myDate,
			    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
		
		long millis = localDateTime
			    .atZone(ZoneId.systemDefault())
			    .toInstant().toEpochMilli();
		
		return millis;
	}
	
	
	public static List<String> readFile(String inputFile) throws IOException {
		
		List<String> lines = new ArrayList<String>();
		
		File f = new File(inputFile);
		
		if(f.exists() && !f.isDirectory() && f.length() > 0) { 
						
			BufferedReader br = new BufferedReader(new FileReader(f));
			
			String line;
			
			while((line = br.readLine()) != null) {
				lines.add(line);
			}
		}
	
        return lines;
	}

}


