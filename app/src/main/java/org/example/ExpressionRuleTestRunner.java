package com.example;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.http.HttpRequestInterceptor;
import org.apache.commons.codec.binary.Base64;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

public class ExpressionRuleTestRunner {

    public static void main(String[] args) throws Exception {
        String appianUserName = System.getProperty("appianUserName");
        String appianPassword = new String(Base64.decodeBase64(System.getProperty("appianPasswordEncoded")), StandardCharsets.UTF_8);
        String baseURL = System.getProperty("siteUrl");
        String startAppURL = "/startTest";
        String testStatusURL = "/testStatus";
        String fetchTestResultsURL = "/fetchTestResults";

        try (CloseableHttpClient client = HttpClients.custom()
                .addInterceptorFirst((HttpRequestInterceptor) (httpRequest, httpContext) -> {
                    String auth = appianUserName + ":" + appianPassword;
                    String encodedAuth = Base64.encodeBase64String(auth.getBytes(StandardCharsets.UTF_8));
                    httpRequest.addHeader("Authorization", "Basic " + encodedAuth);
                })
                .build()) {

            System.out.println("Start Expression Rule Tests");

            HttpPost startTestRequest = new HttpPost(baseURL + startAppURL);
            startTestRequest.setHeader("Content-Type", "application/json");
            startTestRequest.setEntity(new StringEntity("{}", StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(startTestRequest)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    String testRunId = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                    System.out.println("Test-run ID: " + testRunId);

                    // Check test run status every minute
                    boolean isTestInProgress = true;
                    while (isTestInProgress) {
                        HttpGet testStatusRequest = new HttpGet(baseURL + testStatusURL + "?id=" + testRunId);
                        try (CloseableHttpResponse statusResponse = client.execute(testStatusRequest)) {
                            String status = new String(statusResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                            if ("IN PROGRESS".equals(status)) {
                                System.out.println("Waiting for test results...");
                                Thread.sleep(60000);
                            } else {
                                isTestInProgress = false;
                            }
                        }
                    }

                    // Fetch test results
                    HttpGet fetchResultsRequest = new HttpGet(baseURL + fetchTestResultsURL + "?id=" + testRunId);
                    try (CloseableHttpResponse resultsResponse = client.execute(fetchResultsRequest)) {
                        String testRunResults = new String(resultsResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

                        // Process results and convert to JUnit format
                        String xslt = new String(ExpressionRuleTestRunner.class.getClassLoader().getResourceAsStream("convertToJunit.xsl").readAllBytes(), StandardCharsets.UTF_8);

                        Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(new StringReader(xslt)));
                        String outputFilename = "testResult" + System.currentTimeMillis() + ".xml";

                        try (FileOutputStream finalXML = new FileOutputStream(outputFilename)) {
                            transformer.transform(new StreamSource(new StringReader(testRunResults)), new StreamResult(finalXML));
                        }
                        System.out.println("Expression Rule Tests Completed, results saved to " + outputFilename);
                    }
                }
            }
        }
    }
}