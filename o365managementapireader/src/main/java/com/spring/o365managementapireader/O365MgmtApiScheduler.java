package com.spring.o365managementapireader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;



@EnableBinding(Source.class)
@Configuration
@EnableScheduling
@EnableAsync
@Component
public class O365MgmtApiScheduler implements CommandLineRunner{
	private final static Logger errorLogger = LoggerFactory.getLogger("o365managementapireader_errors");
	
	@Autowired
	private Source source;
	
	@Value("${o365managementapi.tenant_authority}")
	private String tenantAuthority; 
	
	@Value("${o365managementapi.client_ID}")
    private String clientId;
	
	@Value("${o365managementapi.client_secret}")
    private String clientSecret;
	
	@Value("${o365managementapi.management_default_scope}")
    private String managementApiScope;
	
	@Value("${o365managementapi.list_content_base_url}")
    private String listContentUrlBase;
	
	@Value("${o365managementapi.keystore.location}")
    private String keystore;
	
	@Value("${o365managementapi.keystore.password}")
    private String keypass;
	
	@Value("${o365managementapi.proxy_host}")
    private String proxyHost; 
	
	@Value("${o365managementapi.proxy_port}")
    private int proxyPort;
	
	@Value("${o365managementapi.retries}")
    private int retries;
	
	@Value("${o365managementapi.contentTypes}")
    private String contentTypes;
	
	@Value("${o365managementapi.clientResponseCodes}")
    private String clientErrorCodes;
	
	@Value("${o365managementapi.serverErrorCodes}")
    private String serverErrorCodes;
	
	@Value("${o365managementapi.readTimeout}")
    private String readTimeout;
	
	@Value("${o365managementapi.delayBetweenErrors}")
	private String delayBetweenErrors;
	
	private Proxy proxy; 
	private ConfidentialClientApplication app; 
	private ClientCredentialParameters clientCredentialParam; 
	private String[] clientErrorCodeList;
	private String[] serverErrorCodeList;
	
	@Override
	public void run(String... args) throws Exception {
		clientErrorCodeList = clientErrorCodes.split(",");
		serverErrorCodeList = serverErrorCodes.split(",");
		File file = new File(keystore);
		InputStream pkcs12cert = new FileInputStream(file);
			
		
		proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        app = ConfidentialClientApplication.builder(
        		clientId,
                ClientCredentialFactory.createFromCertificate(pkcs12cert, keypass))
                .authority(tenantAuthority)
                .proxy(proxy)
                .build();

        // With client credentials flows the scope is ALWAYS of the shape "resource/.default", as the
        // application permissions need to be set statically (in the portal), and then granted by a tenant administrator
        clientCredentialParam = ClientCredentialParameters.builder(
                Collections.singleton(managementApiScope))
                .build();
	} 
	
	//@Scheduled(cron = "${cron.expression}")
    //@Scheduled(initialDelay=10000, fixedDelayString  = "${schedule.interval}")
	@Async
	@Scheduled(cron = "${cron.expression}")
    public void sendEvents() {
	    LocalDateTime myObj = LocalDateTime.now();

		String startTime = myObj.minusMinutes(1).truncatedTo(ChronoUnit.MINUTES).toString();
	    String endTime = myObj.truncatedTo(ChronoUnit.MINUTES).toString(); 
	    
	    System.out.println("Getting all logs from between " + startTime + " and " + endTime); 
		//add to log file 
	    O365MgmtApiReader clientApp = new O365MgmtApiReader(listContentUrlBase, startTime, endTime, source, app, clientCredentialParam, proxy, retries, contentTypes, clientErrorCodeList, serverErrorCodeList, Integer.parseInt(readTimeout), Integer.parseInt(delayBetweenErrors));
		
	    try {
			clientApp.runProgram();
		} catch (Exception e) {
			errorLogger.error(e.getMessage());
		}
	}

	
}
