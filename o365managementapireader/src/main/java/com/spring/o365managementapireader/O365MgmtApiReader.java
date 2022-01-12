package com.spring.o365managementapireader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.io.StringReader;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.support.MessageBuilder;

/**
 * This class calls the o365 management api, retrieves the configured data and sends to kafka using 
 * spring cloud springs kafka framework. O365 Management api has a hierarcical structure it uses to 
 * return data - given the large volume of events we are querying. First the api return page(s) of urls
 * pointing to a group of contentUris. ContentUri is a dedicated url to a group of event data. So the 
 * heirarchy goes the following: pageUri --> contentUri --> group of eventData.
 *  
 * @author 5158628
 * @author Vince Nkawu
 * @version 1.1
 *
 */
public class O365MgmtApiReader {
	private final static Logger logger 				= LoggerFactory.getLogger("o365managementapireader");
	private final static Logger errorLogger 		= LoggerFactory.getLogger("o365managementapireader_errors");
	private final static Logger contentUriLogger 	= LoggerFactory.getLogger("o365managementapireader_contentUris");
	private final static Logger nextPageUriLogger 	= LoggerFactory.getLogger("o365managementapireader_nextPageUri");
	private final static Logger contentUriList 		= LoggerFactory.getLogger("o365managementapireader_contentUriList");
	private final static Logger lastContentUri 		= LoggerFactory.getLogger("o365managementapireader_lastContentUri");
	
    private final String list_content_url_base;  
    private final Proxy proxy;  
    private final String startTime;
    private final String endTime; 
    private final Source source; 
	private final ConfidentialClientApplication app; 
	private final ClientCredentialParameters clientCredentialParam; 
	private final OkHttpClient okHttpClient = new OkHttpClient();
	private final int retries;
	private final String contentTypes; 
	private final String[] clientErrorCodeList;
	private final String[] serverErrorCodeList;
	private final int readTimeout;
	private final int delayBetweenErrors; 
    
    //constructors 
    public O365MgmtApiReader(String list_content_url_base, String startTime, String endTime, Source source, ConfidentialClientApplication app, ClientCredentialParameters clientCredentialParam, Proxy proxy, int retries, String contentTypes, String [] clientErrorCodeList, String [] serverErrorCodeList, int readTimeout, int delayBetweenErrors) {
	    this.list_content_url_base = list_content_url_base;
    	this.proxy = proxy; 
    	this.startTime = startTime;
    	this.endTime = endTime; 
    	this.source = source; 
    	this.app = app; 
    	this.clientCredentialParam = clientCredentialParam; 
    	this.retries = retries;
    	this.contentTypes = contentTypes;
    	this.clientErrorCodeList = clientErrorCodeList;
    	this.serverErrorCodeList = serverErrorCodeList;
    	this.readTimeout = readTimeout; 
    	this.delayBetweenErrors = delayBetweenErrors; 
    }
    
    /**
     * runProgram kicks off the reader, first storing all the contentTypes we will get data for
     * then making the appropriate api call to get their respective data 
     * @throws Exception
     */
    public void runProgram() throws Exception{

        try {          
        	String[] contentTypeList = contentTypes.split(",");
        	for(String contentType:contentTypeList) {
        		 getContentLogs(contentType);
        	}           
        } catch(Exception ex){
            System.out.println("Oops! We have an exception of type - " + ex.getClass());
            errorLogger.error(ex.getMessage()+ "exception encountered");
        }
    }
    
    /**
     * Given the top level query for event data for any one of the contentTypes, 
     * This method is used to first build a list of contentUris, then it makes an api
     * call to each contentUri to retrieve the events
     * @param contentType
     */
    public void getContentLogs(String contentType){
        ArrayList <String> contentList	= new ArrayList <String>();		// using array list to return response and nextUrl
        String url=list_content_url_base+contentType+"&startTime="+startTime+"&endTime="+endTime; 

        logger.info("**Top of our query for " + contentType + " contents logged between " + startTime + " and " + endTime);  
		//keep get first page and keep getting next page of contents to build contentUri list till no more page left
		do{
			contentList=getContents(url, 0, contentType);
			if(!contentList.isEmpty()) {
				ArrayList <String> contentUris = new ArrayList <String>(); 		// used to build a list of all content Uris 
	        	contentUris.addAll(getContentUriList(contentList.get(0)));
	        	url = contentList.get(1);
	        	//trackNextUriLogger.info(url);
	        	
	        	//Now make a get request against each contentUri in the list to retrieve data 
	    		logger.info("Begin making GET request against contentUris");
	    		for (String contentUri : contentUris) { 
	    			getContentbyUri(contentUri, 0);
	    		}
	        } 
			else {
				url=null; 
			}
		} while (url!=null);
		
		
    }

    /**
     * getContents is used to retrieve the page of contentUris (top of response heirarchy for the o365 management api
     * we will also get the nextPageUri - paganation used to nevigate through all contentUris until we get to the last page
     * @param url
     * @param retry
     * @param contentType
     * @return an ArrayList containing page of Uris and nextPageurl 
     */
     
     
    public ArrayList <String> getContents(String url, int retry, String contentType) {
    	ArrayList <String> responseAndNextUrl = new ArrayList <String>(); 
    	OkHttpClient client = okHttpClient.newBuilder()
    							.proxy(proxy)
    							.readTimeout(readTimeout, TimeUnit.MILLISECONDS)
    							.build();

    	String accessToken=getToken(0);
		
		logger.info("Retrieving page of " + contentType + " contentUris");
		logger.info("Current page url: "+url);
		Request request = new Request.Builder()
				.url(url)
				.header("Accept", "application/json")
				.header("Authorization", "Bearer " + accessToken)
				.build();
		
		logger.debug(request.toString());
		logger.debug(request.headers().toString());
		
		Response response;
		
		try {
			response = client.newCall(request).execute();
			String responseString = response.body().string(); 
			
			if (Arrays.asList(clientErrorCodeList).contains(Integer.toString(response.code()))) {
				
				if(response.code()==401 && responseString.contains("AF10001")) {
					retry++;
					int sleepTime = delayBetweenErrors * retry; 
					logger.warn(response.code()+ " response - Client side permission error. Retrying in "+(sleepTime/1000)+ "s");
					TimeUnit.MILLISECONDS.sleep(sleepTime);
					
					if(retry <= retries) {
		            	logger.info("Retry "+ retry + " for getting data associated with the nextUri");;  
		            	responseAndNextUrl = getContents(url, retry, contentType); 
		            	return responseAndNextUrl;
		            }else {
		            	errorLogger.error("nextUri: Unable to get data after "+retries+" retries constantly getting " +response.code()+" error ");
		            	errorLogger.error(responseString);			//ovo will alert on this 
		            	nextPageUriLogger.error(url);
		            }
				}else {
					nextPageUriLogger.error(url);
					errorLogger.error(responseString);			//ovo will alert on this 
				}
				//System.exit(0);   
				
			}else if (Arrays.asList(serverErrorCodeList).contains(Integer.toString(response.code()))) { 
				retry++;
				int sleepTime = delayBetweenErrors * retry; 
				logger.warn(response.code()+ " response - Server Side Error. Retrying in "+(sleepTime/1000)+ "s");
		        TimeUnit.MILLISECONDS.sleep(sleepTime);
		        
	            if(retry <= retries) {
	            	logger.info("Retry "+ retry + " after response code "+ response.code() + " encountered");  
	            	responseAndNextUrl = getContents(url, retry, contentType); 
	            	return responseAndNextUrl;
	            }else {
	            	errorLogger.error(response.code()+" continuously encountered, unable to pull data from " + url);
	            	nextPageUriLogger.error(url);
	            }
			}
			
			logger.info(response.message() +" Response***");
			logger.info("***ResponseCode:" + response.code());
			logger.debug("***Response:" + responseString);

			responseAndNextUrl.add(responseString);
			String nextPageUri = response.header("NextPageUri");
			responseAndNextUrl.add(nextPageUri); 
			
		} catch (SocketTimeoutException ex) { 
			try {
				logger.warn(ex + " when trying to retrieve data");
	            retry++;
	            int sleepTime = delayBetweenErrors * retry; //increase delay time geometrically - T, 2T, 3T
	            TimeUnit.MILLISECONDS.sleep(sleepTime);
	            
	            if(retry <= retries) {
	            	logger.info("Retry "+ retry + "for getting data associated with url");  
	            	responseAndNextUrl = getContents(url, retry, contentType); 	 
	            	return responseAndNextUrl;
	            }else {
	            	errorLogger.error("NextPageUri: Socket Connection Timed out "+ retries +"X retrieveing data from " + url);
	            	nextPageUriLogger.error(url);
	            } 
			}catch (InterruptedException e) {
				errorLogger.error(e.getMessage());
				nextPageUriLogger.error(url);
			}
            
        } catch (IOException e) {
			errorLogger.error(e.getMessage());
			nextPageUriLogger.error(url);
		} catch (InterruptedException e) {
			errorLogger.error(e.getMessage());
			nextPageUriLogger.error(url);
		} catch (Exception e) {
			errorLogger.error(e.getMessage());
			nextPageUriLogger.error(url);
		}
		
		return responseAndNextUrl;
    }
    
    /**
     * This method will convert Json Array object into a string ArrayList with contentUris
     * @param jsonString
     * @return
     */
    public ArrayList <String> getContentUriList(String jsonString) {
    	
    	ArrayList <String> contentUris = new ArrayList <String> (); 
        //JsonElement jsonTree = JsonParser.parseString(jsonString);
        
        JsonReader reader = new JsonReader(new StringReader(jsonString));
        reader.setLenient(true);
        JsonElement jsonTree = Streams.parse(reader);
        //new JsonParser().parse(reader);
        
        if(jsonTree.isJsonArray()) {
            JsonArray contents = jsonTree.getAsJsonArray();
            for (JsonElement content : contents) {
            	JsonObject contentObj = content.getAsJsonObject();
            	contentUris.add(contentObj.get("contentUri").toString()); 
            	contentUriList.info(contentObj.get("contentUri").toString());
            }
        }
        return contentUris; 
    }
    
    public void parseContentUriList(String jsonString) {
        //JsonElement jsonTree = JsonParser.parseString(jsonString);
    	JsonReader reader = new JsonReader(new StringReader(jsonString));
        reader.setLenient(true);
        JsonElement jsonTree = Streams.parse(reader);
        int recordCount = 0; 
        
        if(jsonTree.isJsonArray()) {
        	logger.info("parsing contentUri response");
        	logger.info("*sending message to Kafka*");
            JsonArray contents = jsonTree.getAsJsonArray();
            for (JsonElement content : contents) {
            	JsonObject contentObj = content.getAsJsonObject();
            	sendMessageToKafka(contentObj.toString());
            	recordCount++;
            }
            logger.info("*All msgs sent*");
            logger.debug("Record Count: "+recordCount);
        }
        
    }
    
   /**
    * This method will facilitate the api call to a contentUri, break each response into respective events 
    * and then calls the sendMessageToKafka method to send each event to kafka
    * @param contentUri
    * @param retry
    */
    public void getContentbyUri(String contentUri, int retry) {
    	contentUri = contentUri.substring(1, contentUri.length()-1);		//remove the start/end quotes from the string 
    	OkHttpClient client = okHttpClient.newBuilder()
    							.proxy(proxy)
    							.readTimeout(readTimeout, TimeUnit.MILLISECONDS)
    							.build();
    	
    	String accessToken = getToken(0);
		
		logger.info("** Making GET request to retrieve contentUris Data.");
		logger.debug("url: "+contentUri);
		Request request = new Request.Builder()
				.url(contentUri)
				.header("Accept", "application/json")
				.header("Authorization", "Bearer " + accessToken)
				.build();
		
		logger.debug(request.toString());
		logger.debug(request.headers().toString());
		
		Response response;
		try {
			response = client.newCall(request).execute();
			String responseString = response.body().string(); 
			
			if (Arrays.asList(clientErrorCodeList).contains(Integer.toString(response.code()))) {
				if(response.code()==401 && responseString.contains("AF10001")) {
					retry++;
					int sleepTime = delayBetweenErrors * retry;
					logger.warn(response.code()+ " response - Client side permission error. Retrying in "+(sleepTime/1000)+ "s");
					TimeUnit.MILLISECONDS.sleep(sleepTime);
					
					if(retry <= retries) {
		            	logger.info("Retry "+ retry + " for getting data associated with the contentUri");;  
		            	getContentbyUri(" "+contentUri+" ", retry);
		            	return;
		            }else {
		            	errorLogger.error("contentUri: Unable to get data after "+retries+" retries constantly getting " +response.code()+" error \nThis content's data isn't written to kafka");
		            	errorLogger.error(responseString);			//ovo will alert on this
		            	contentUriLogger.error(contentUri);
		            }
				}else {
					contentUriLogger.error(contentUri);
					errorLogger.error(responseString);			//ovo will alert on this
				}
				//System.exit(0);   
				
			}else if (Arrays.asList(serverErrorCodeList).contains(Integer.toString(response.code()))) { 
				retry++;
				int sleepTime = delayBetweenErrors * retry; 
				logger.warn(response.code()+ " response - Server Side Error. Retrying in "+(sleepTime/1000)+ "s");
		        TimeUnit.MILLISECONDS.sleep(sleepTime);
	       
	            if(retry <= retries) {
	            	logger.info("Retry "+ retry + " for getting data associated with the contentUri");;  
	            	getContentbyUri(" "+contentUri+" ", retry);
	            	return;
	            }else {
	            	errorLogger.error("contentUri: Unable to get data after "+retries+" retries constantly getting " +response.code()+" error \nThis content's data isn't written to kafka");
	            	contentUriLogger.error(contentUri);
	            }
			}
						
			logger.info(response.message() +" Response***");
			logger.info("***ResponseCode:" + response.code());
			logger.debug("***Response:" + responseString);
			parseContentUriList(responseString);
			lastContentUri.info(contentUri);
			
		} catch (SocketTimeoutException ex) {
			try {
			       retry++;
		            int sleepTime = delayBetweenErrors * retry; //increase delay time geometrically - T, 2T, 3T
		            logger.warn(ex + "when trying to retrieve data for ContentUri. Retrying in "+(sleepTime/1000)+ "s");
		            TimeUnit.MILLISECONDS.sleep(sleepTime);
		            
		            if(retry <= retries) {
		            	logger.info("Retry "+ retry + " for getting data associated with the contentUri");
		            	getContentbyUri(" "+contentUri+" ", retry);
		            	return;
		            }else {
		            	errorLogger.error("contentUri: Unable to get data after "+retries+" retries due to "+ex.getMessage() +"\nThis content's data isn't written to kafka");
		            	
		            }
			}catch (InterruptedException e) {
				errorLogger.error(e.getMessage());
				contentUriLogger.error(contentUri);
			}
     
             
        } catch (IOException e) {
			errorLogger.error(e.getMessage());
			contentUriLogger.error(contentUri);
		} catch (InterruptedException e) {
			errorLogger.error(e.getMessage());
			contentUriLogger.error(contentUri);
		} catch (Exception e) {
			errorLogger.error(e.getMessage());
			contentUriLogger.error(contentUri);
		}
    }
    
    /**
     * This method simply sends msg to kafka using the org.springframework.cloud.stream.messaging.Source object
     * @param msg
     */
    public void sendMessageToKafka(String msg) {
		try {
			Message<String> message = MessageBuilder.withPayload(msg).build();
			logger.debug("Payload of the Message to be sent = " + message.getPayload().toString());
			source.output().send(message);
		} catch (MessageHandlingException ex) {
			errorLogger.error("Message Handling Exception: " + ex.getMessage());
		} catch (RecordTooLargeException recordToLarge) {
			errorLogger.error("Record too large exception: " + recordToLarge.getMessage());
		} catch (Exception ex) {
			errorLogger.error(ex.getMessage());
		}
	}
    
    /**
     * this method will use the ConfidentialClientApplication app to re-acquire a token. An acquired token is 
     * cached for an hour, and when the tokens ttyl is near expiration the ConfidentialClientApplication app 
     * will make a call out to Azure to refresh the auth token
     * 
     * @param retry
     * @return accessToken
     */
    public String getToken(int retry) {
    	String accessToken="";
    	try {
			accessToken = app.acquireToken(clientCredentialParam).get().accessToken();
		} catch (Exception e1) {
			retry++;
			int sleepTime = (delayBetweenErrors/4) * retry; 	//allow the app to sleep just long enough to bypass whatever is impacting communication wit azure AD
			logger.warn(e1.getMessage() + ": when trying to get access token, retrying in "+(sleepTime/1000)+ "s");
	        try {
				TimeUnit.MILLISECONDS.sleep(sleepTime);
			} catch (InterruptedException e) {
				errorLogger.error(e.getMessage());
				//e.printStackTrace();
			}
			if (retry <= retries) {
				logger.info("Retry "+ retry +" for getting access token");
				accessToken = getToken(retry);
			} else {
				errorLogger.error("Unable to get access token after " + retries + " attempts");
			}
		}
    	return accessToken;
    }
}
