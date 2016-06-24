package com.itemis.maven.plugins.cdi.hooks.http;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.repackaged.com.google.common.base.Objects;
import com.google.api.client.util.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;
import com.itemis.maven.plugins.cdi.logging.Logger;

@ProcessingStep(id = "httpRequest")
public class HttpRequestHook implements CDIMojoProcessingStep {
  private static final String KEY_METHOD = "method";
  private static final String KEY_URL = "url";
  // header1=>xyz,header2=>xyz
  private static final String KEY_HEADER = "header";
  private static final String KEY_USERNAME = "user";
  private static final String KEY_PASSWORD = "password";

  @Inject
  private Logger log;

  @Override
  public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
    if (!context.hasMappedData()) {
      this.log
          .warn("No request information specified! Skipping execution of hook '" + context.getCompositeStepId() + "'.");
      return;
    }

    String connectionUrl = context.getMappedDate(KEY_URL);
    if (connectionUrl == null) {
      throw new MojoExecutionException("No connection URL specified for hook '" + context.getCompositeStepId() + "'");
    }

    try {
      HttpRequestFactory requestFactory = createRequestFactory(context, false);
      GenericUrl url = new GenericUrl(connectionUrl);
      HttpRequest request = createRequest(context, requestFactory, url, false);
      HttpResponse response = request.execute();
      processResponse(response, request);
    } catch (Exception e) {
      throw new MojoExecutionException(
          "An unexpected exception was caught during the execution of a hook HTTP request: " + e.getMessage(), e);
    }
  }

  @RollbackOnError
  public void rollback(ExecutionContext context) throws MojoExecutionException {
    if (!context.hasMappedRollbackData()) {
      this.log
          .debug("No rollback commands to execute! Skipping rollback of hook '" + context.getCompositeStepId() + "'.");
      return;
    }

    String connectionUrl = context.getMappedRollbackDate(KEY_URL);
    if (connectionUrl == null) {
      throw new MojoExecutionException(
          "No rollback connection URL specified for hook '" + context.getCompositeStepId() + "'");
    }

    try {
      HttpRequestFactory requestFactory = createRequestFactory(context, true);
      GenericUrl url = new GenericUrl(connectionUrl);
      HttpRequest request = createRequest(context, requestFactory, url, true);
      HttpResponse response = request.execute();
      processResponse(response, request);
    } catch (Exception e) {
      throw new MojoExecutionException(
          "An unexpected exception was caught during the execution of a hook HTTP request: " + e.getMessage(), e);
    }
  }

  private HttpRequest createRequest(ExecutionContext context, HttpRequestFactory requestFactory, GenericUrl url,
      boolean rollback) throws MojoExecutionException {
    String methodString = Objects
        .firstNonNull(rollback ? context.getMappedRollbackDate(KEY_METHOD) : context.getMappedDate(KEY_METHOD), "GET")
        .toUpperCase();
    HttpMethod method = HttpMethod.GET;
    try {
      method = HttpMethod.valueOf(methodString);
    } catch (Exception e) {
      throw new MojoExecutionException("Could not parse '" + methodString
          + "' as a HTTP method. Supported methods are: " + Joiner.on(',').join(Sets.newHashSet(HttpMethod.values())));
    }

    try {
      // QUESTION parameters, content?
      HttpRequest request = null;
      HttpContent content;
      switch (method) {
        case DELETE:
          request = requestFactory.buildDeleteRequest(url);
          break;
        case GET:
          request = requestFactory.buildGetRequest(url);
          break;
        case POST:
          content = new ByteArrayContent("application/x-www-form-urlencoded", "".getBytes());
          request = requestFactory.buildPostRequest(url, content);
          break;
        case PUT:
          content = new ByteArrayContent("application/x-www-form-urlencoded", "".getBytes());
          request = requestFactory.buildPutRequest(url, content);
          break;
      }

      addHeaders(request, context, rollback);
      return request;
    } catch (IOException e) {
      throw new MojoExecutionException("Unable to build HTTP " + method + " request with url '" + url + "'.", e);
    }
  }

  private HttpRequestFactory createRequestFactory(ExecutionContext context, boolean rollback) {
    String username = rollback ? context.getMappedRollbackDate(KEY_USERNAME) : context.getMappedDate(KEY_USERNAME);
    String password = rollback ? context.getMappedRollbackDate(KEY_PASSWORD) : context.getMappedDate(KEY_PASSWORD);

    HttpRequestFactory requestFactory;
    if (username != null) {
      HttpRequestInitializer initializer = new BasicAuthentication(username, password);
      requestFactory = new NetHttpTransport().createRequestFactory(initializer);
    } else {
      requestFactory = new NetHttpTransport().createRequestFactory();
    }

    return requestFactory;
  }

  private void addHeaders(HttpRequest request, ExecutionContext context, boolean rollback) {
    HttpHeaders headers = new HttpHeaders();
    int i = 1;
    while (rollback ? context.containsMappedRollbackDate(KEY_HEADER + i) : context.containsMappedDate(KEY_HEADER + i)) {
      List<String> header = Splitter.on(':').splitToList(
          rollback ? context.getMappedRollbackDate(KEY_HEADER + i) : context.getMappedDate(KEY_HEADER + i));
      if (header.size() == 2) {
        headers.put(header.get(0), header.get(1));
      } else {
        headers.put(header.get(0), "");
      }
      i++;
    }
    request.setHeaders(headers);
  }

  private void processResponse(HttpResponse response, HttpRequest request)
      throws MojoFailureException, MojoExecutionException {
    int status = response.getStatusCode();
    if (status / 100 != 2) {
      throw new MojoFailureException("The " + request.getRequestMethod() + " request to '" + request.getUrl()
          + "' was not successful. Status code: " + status + " Message: " + response.getStatusMessage());
    }

    try {
      ByteStreams.copy(response.getContent(), System.out);
      System.out.println();// empty line for line break after the content
    } catch (IOException e) {
      throw new MojoExecutionException("Problem reading and printing response content.", e);
    }
  }

  private enum HttpMethod {
    DELETE, GET, POST, PUT;
  }
}
