package com.itemis.maven.plugins.cdi.hooks.mvn;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;
import com.itemis.maven.plugins.cdi.logging.Logger;

@ProcessingStep(id = "mvn", description = "Invoke a separate Maven build process during your processing logic.")
public class MavenHook implements CDIMojoProcessingStep {
  @Inject
  private Logger log;
  @Inject
  private MavenProject project;
  @Inject
  private Settings settings;
  @Inject
  @Named("maven.home")
  private String mavenHome;

  @Override
  public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
    if (!context.hasUnmappedData()) {
      this.log.warn("No goals for Maven execution! Skipping hook '" + context.getCompositeStepId() + "'.");
      return;
    }

    for (String date : context.getUnmappedData()) {
      executeMavenCall(date, context, false);
    }
  }

  private void executeMavenCall(String data, ExecutionContext context, boolean isRollback) throws MojoFailureException {
    InvocationRequest request = new DefaultInvocationRequest();
    request.setPomFile(this.project.getFile());

    List<String> goals = Lists.newArrayList();
    List<String> profiles = Lists.newArrayList();
    List<String> opts = Lists.newArrayList();

    Iterator<String> tokens = Splitter.on(' ').splitToList(data).iterator();
    while (tokens.hasNext()) {
      String token = tokens.next(); 
      if ("-P".equals(token) || "--activate-profiles".equals(token)) {
        profiles.add(tokens.next());
      } else if (token.startsWith("-D")) {
        opts.add(token);
      } else {
        goals.add(token);
      }
    }

    request.setGoals(goals);
    request.setMavenOpts(Joiner.on(' ').join(opts));
    request.setProfiles(profiles);
    request.setShellEnvironmentInherited(true);
    request.setOffline(this.settings.isOffline());
    request.setInteractive(this.settings.isInteractiveMode());

    this.log.info((isRollback ? "Rolling back " : "Executing ") + "hook " + context.getCompositeStepId()
        + " with the following setup:");
    this.log.info("\t\tGOALS: " + Joiner.on(' ').join(goals));
    this.log.info("\t\tOPTIONS: " + Joiner.on(' ').join(opts));
    this.log.info("\t\tPROFILES: " + Joiner.on(' ').join(profiles));

    Invoker invoker = new DefaultInvoker();
    setMavenHome(invoker);
    try {
      InvocationResult result = invoker.execute(request);
      if (result.getExitCode() != 0) {
        throw new MojoFailureException(
            "Error during execution of hook: " + result.getExecutionException().getMessage());
      }
    } catch (MavenInvocationException e) {
      throw new MojoFailureException(e.getMessage(), e);
    }
  }

  private void setMavenHome(Invoker invoker) {
    String path = null;
    if (isValidMavenHome(this.mavenHome)) {
      path = this.mavenHome;
    } else {
      String sysProp = System.getProperty("maven.home");
      if (isValidMavenHome(sysProp)) {
        path = sysProp;
      } else {
        String envVar = System.getenv("M2_HOME");
        if (isValidMavenHome(envVar)) {
          path = envVar;
        }
      }
    }

    if (path != null) {
      this.log.debug("Using maven home: " + path);
      invoker.setMavenHome(new File(path));
    }
  }

  private boolean isValidMavenHome(String path) {
    if (path != null) {
      File homeFolder = new File(path);
      return homeFolder.exists() && homeFolder.isDirectory() && new File(homeFolder, "bin/mvn").exists();
    }
    return false;
  }

  @RollbackOnError
  public void rollback(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
    if (!context.hasUnmappedRollbackData()) {
      this.log.debug(
          "No rollback goals for Maven execution! Skipping rollback of hook '" + context.getCompositeStepId() + "'.");
      return;
    }

    for (String date : context.getUnmappedRollbackData()) {
      executeMavenCall(date, context, true);
    }
  }
}
