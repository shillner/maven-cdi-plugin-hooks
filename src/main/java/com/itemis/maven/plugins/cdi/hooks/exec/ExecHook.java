package com.itemis.maven.plugins.cdi.hooks.exec;

import java.util.List;

import javax.inject.Inject;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;
import com.itemis.maven.plugins.cdi.logging.Logger;

@ProcessingStep(id = "exec", description = "Executes shell commands such as shell or batch script execution.")
public class ExecHook implements CDIMojoProcessingStep {
  @Inject
  private Logger log;

  @Override
  public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
    // TODO let the user set the execution directory and environment using the mapped data feature
    if (!context.hasUnmappedData()) {
      this.log.warn("No commands to execute! Skipping hook '" + context.getCompositeStepId() + "'.");
      return;
    }

    List<List<String>> commandsAndArgs = Lists.newArrayList();
    for (String date : context.getUnmappedData()) {
      commandsAndArgs.add(Splitter.on(' ').splitToList(date));
    }

    this.log.info("Executing hook " + context.getCompositeStepId() + " with the following setup:");
    for (int i = 0; i < commandsAndArgs.size(); i++) {
      this.log.info("\t\tCOMMAND " + (i + 1) + ": " + Joiner.on(' ').join(commandsAndArgs.get(i)));
    }

    for (List<String> command : commandsAndArgs) {
      executeCommand(command);
    }
  }

  @RollbackOnError
  public void rollback(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
    if (!context.hasUnmappedRollbackData()) {
      this.log
          .debug("No rollback commands to execute! Skipping rollback of hook '" + context.getCompositeStepId() + "'.");
      return;
    }

    List<List<String>> commandsAndArgs = Lists.newArrayList();
    for (String date : context.getUnmappedRollbackData()) {
      commandsAndArgs.add(Splitter.on(' ').splitToList(date));
    }

    this.log.info("Rolling back hook " + context.getCompositeStepId() + " with the following setup:");
    for (int i = 0; i < commandsAndArgs.size(); i++) {
      this.log.info("\t\tCOMMAND " + (i + 1) + ": " + Joiner.on(' ').join(commandsAndArgs.get(i)));
    }

    for (List<String> command : commandsAndArgs) {
      executeCommand(command);
    }
  }

  private void executeCommand(List<String> command) throws MojoExecutionException, MojoFailureException {
    this.log.debug("Running command: " + Joiner.on(' ').join(command));

    ProcessBuilder builder = new ProcessBuilder(command.toArray(new String[command.size()])).inheritIO();
    try {
      Process p = builder.start();
      int returnCode = p.waitFor();
      if (returnCode != 0) {
        throw new MojoFailureException(
            "An error occurred during the execution of a hook command. Return code was " + returnCode);
      }
      this.log.debug("Command execution finished successfully.");
    } catch (Exception e) {
      throw new MojoExecutionException(
          "An unexpected exception was caught during the execution of a hook command: " + e.getMessage(), e);
    }
  }
}
