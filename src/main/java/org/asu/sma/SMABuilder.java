package org.asu.sma;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author aesanch2, gwalliman
 * Main class that Jenkins interfaces with
 * This class uses the Metadata API and the Migration Tool
 * Go here to learn about Migration Tool: https://developer.salesforce.com/docs/atlas.en-us.198.0.daas.meta/daas/forcemigrationtool_connect.htm
 */
public class SMABuilder extends Builder
{
    private SMAGit git;
    private boolean rollbackEnabled;
    private boolean updatePackageEnabled;
    private boolean forceInitialBuild;
    private boolean runUnitTests;
    private boolean validateEnabled;
    private String sfUsername;
    private String sfPassword;
    private String sfServer;
    private String forceSha;
    private JSONObject generateManifests;
    private JSONObject generateAntEnabled;


    @DataBoundConstructor
    public SMABuilder(JSONObject generateManifests,
                      JSONObject generateAntEnabled,
                      String forceSha)
    {
        this.generateManifests = generateManifests;
        if(generateManifests != null)
        {
            rollbackEnabled = Boolean.valueOf(generateManifests.get("rollbackEnabled").toString());
            updatePackageEnabled = Boolean.valueOf(generateManifests.get("updatePackageEnabled").toString());
            forceInitialBuild = Boolean.valueOf(generateManifests.get("forceInitialBuild").toString());
        }

        this.generateAntEnabled = generateAntEnabled;
        if(generateAntEnabled != null)
        {
            sfUsername = generateAntEnabled.get("sfUsername").toString();
            sfPassword = generateAntEnabled.get("sfPassword").toString();
            sfServer = generateAntEnabled.get("sfServer").toString();
            validateEnabled = Boolean.valueOf(generateAntEnabled.get("validateEnabled").toString());
            runUnitTests = Boolean.valueOf(generateAntEnabled.get("runUnitTests").toString());
        }

        this.forceSha = forceSha;
    }

    /**
     * This is called when a build is actually run
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
    {
        boolean apexChangePresent = true;
        boolean forceInitialBuildOverride = false;
        String forceShaOverride;
        String newCommit;
        String prevCommit;
        String jenkinsGitUserName;
        String jenkinsGitEmail;
        String workspaceDirectory;
        String jobName;
        String buildTag;
        String buildNumber;
        String jenkinsHome;
        ArrayList<String> listOfDestructions, listOfUpdates;
        ArrayList<SMAMetadata> members;
        EnvVars envVars;
        List<ParameterValue> parameterValues;

        try
        {
            //Load our environment variables from the build for the job
            envVars = build.getEnvironment(listener);

            newCommit = envVars.get("GIT_COMMIT");
            prevCommit = envVars.get("GIT_PREVIOUS_SUCCESSFUL_COMMIT");
            jenkinsGitUserName = envVars.get("GIT_COMMITTER_NAME");
            jenkinsGitEmail = envVars.get("GIT_COMMITTER_EMAIL");
            workspaceDirectory = envVars.get("WORKSPACE");
            jobName = envVars.get("JOB_NAME");
            buildTag = envVars.get("BUILD_TAG");
            buildNumber = envVars.get("BUILD_NUMBER");
            jenkinsHome = envVars.get("JENKINS_HOME");

            //Handle SMA environment variables
            String envVarShaOverride = envVars.get("SMA_SHA_OVERRIDE");
            if (envVarShaOverride != null)
            {
                forceShaOverride = envVarShaOverride;
            }
            else
            {
                forceShaOverride = "";
            }

            String envVarForceOverride = envVars.get("SMA_FORCE_INITIAL_BUILD");
            if (envVarForceOverride != null)
            {
                forceInitialBuildOverride = Boolean.valueOf(envVarForceOverride);
            }

            //Create a deployment space for this job within the workspace
            File deployStage = new File(workspaceDirectory + "/sma");
            if (deployStage.exists())
            {
                FileUtils.deleteDirectory(deployStage);
            }
            deployStage.mkdirs();

            //Put the deployment stage location into the environment as a variable
            parameterValues = new ArrayList<ParameterValue>();
            parameterValues.add(new StringParameterValue("SMA_DEPLOY", deployStage.getPath() + "/src"));
            String pathToRepo = workspaceDirectory + "/.git";

            //Determine which git wrapper to use based on project configuration and build variables

            //If we have provided a SHA (forceSha), use that SHA as the previous commit
            if (!forceShaOverride.isEmpty())
            {
                prevCommit = forceShaOverride;
                git = new SMAGit(pathToRepo, newCommit, prevCommit);
            }
            else if (!getForceSha().isEmpty())
            {
                prevCommit = getForceSha();
                git = new SMAGit(pathToRepo, newCommit, prevCommit);
            }
            //If we are forcing a job (manual trigger), or if this is the first build or initial commit, deploy everything
            else if (forceInitialBuildOverride || getForceInitialBuild() || prevCommit == null)
            {
                prevCommit = null;
                git = new SMAGit(pathToRepo, newCommit);
            }
            //Otherwise, use the last successful commit from Jenkins
            else
            {
                git = new SMAGit(pathToRepo, newCommit, prevCommit);
            }

            //At this point we have a Git wrapper representing the change occurring in this build

            //Check to see if we need to generate the manifest files
            //The manifest lists out all the files that will be moved
            if (getGenerateManifests())
            {
                //Get our change sets
                //This contains all the files that were deleted
                listOfDestructions = git.getDeletions();
                //This contains all the files that were changed or updated
                listOfUpdates = git.getNewChangeSet();

                //Generate representations of the manifests (both package manifest and destructiveChanges manifest if necessary)
                members = SMAUtility.generate(listOfDestructions, listOfUpdates, deployStage.getPath());

                //Check whether we need to run the unit tests
                apexChangePresent = SMAUtility.apexChangesPresent(members);

                //At this point we have created our manifest files
                //Drop some debugs
                listener.getLogger().println("[SMA] - Created deployment package.");
                printMembers(listener, members);

                //Copy the files to the deployStage
                SMAUtility.replicateMembers(members, workspaceDirectory, deployStage.getPath());

                //If we are going to create a set of rollback packages that will reverse our change
                if (getRollbackEnabled() && prevCommit != null)
                {
                    String rollbackDirectory = jenkinsHome + "/jobs/" + jobName + "/builds/" + buildNumber + "/sma/rollback";
                    File rollbackStage = new File(rollbackDirectory);

                    //Delete an existing rollback if it exists.
                    if (rollbackStage.exists())
                    {
                        FileUtils.deleteDirectory(rollbackStage);
                    }
                    rollbackStage.mkdirs();

                    //Get our lists of changes
                    ArrayList<String> listOfOldItems = git.getOldChangeSet();
                    ArrayList<String> listOfAdditions = git.getAdditions();

                    //Generate the manifests for the rollback package
                    //Note that the arguments are reversed since we are deleting the additions and adding the deletions
                    ArrayList<SMAMetadata> rollbackMembers = SMAUtility.generate(listOfAdditions, listOfOldItems, rollbackDirectory);

                    //Copy the files in the rollbackMembers manifest to the rollbackDirectory directory
                    git.getPrevCommitFiles(rollbackMembers, rollbackDirectory);

                    //Zip up the files in the rollback directory
                    String zipFile = SMAUtility.zipRollbackPackage(rollbackStage, buildTag);

                    //Delete the rollback directory
                    FileUtils.deleteDirectory(rollbackStage);

                    //Save info about the file to Jenkins
                    parameterValues.add(new StringParameterValue("SMA_ROLLBACK", zipFile));
                    listener.getLogger().println("[SMA] - Created rollback package.");
                }

                //Check to see if we need to update the repository's package.xml file
                if (getUpdatePackageEnabled())
                {
                    //If we have any changes, this function saves package.xml to the Git directory and commits it
                    boolean updateRequired = git.updatePackageXML(workspaceDirectory, jenkinsGitUserName, jenkinsGitEmail);
                    if (updateRequired)
                    {
                        listener.getLogger().println("[SMA] - Updated repository package.xml file.");
                    }
                }
            }

            //Check to see if we need to generate the build file
            if (getGenerateAntEnabled())
            {
                SMAPackage buildPackage = new SMAPackage(deployStage.getPath(), git.getContents(),
                        jenkinsHome, getDescriptor().getRunTestRegex(), getDescriptor().getPollWait(),
                        getDescriptor().getMaxPoll(), getRunUnitTests(), getValidateEnabled(),
                        getSfUsername(), getSfPassword(), getSfServer());

                //Generate the build file, which will instruct SF to
                String buildFile = SMABuildGenerator.generateBuildFile(buildPackage, apexChangePresent);
                listener.getLogger().println("[SMA] - Created build file.");
                parameterValues.add(new StringParameterValue("SMA_BUILD", buildFile));
            }

            build.addAction(new ParametersAction(parameterValues));
        }
        catch (Exception e)
        {
            e.printStackTrace(listener.getLogger());
            return false;
        }

        return true;
    }

    public boolean getGenerateManifests()
    {
        if(generateManifests == null)
        {
            return false;
        }
        else
        {
            return true;
        }
    }

    public boolean getGenerateAntEnabled()
    {
        if(generateAntEnabled == null)
        {
            return false;
        }
        else
        {
            return true;
        }
    }

    public String getForceSha()
    {
        if (forceSha == null)
        {
            return "";
        }
        else
        {
            return forceSha;
        }
    }

    public boolean getRollbackEnabled()
    {
        return rollbackEnabled;
    }

    public boolean getUpdatePackageEnabled()
    {
        return updatePackageEnabled;
    }

    public boolean getForceInitialBuild()
    {
        return forceInitialBuild;
    }

    public boolean getRunUnitTests()
    {
        return runUnitTests;
    }

    public boolean getValidateEnabled()
    {
        return validateEnabled;
    }

    public String getSfUsername()
    {
        return sfUsername;
    }

    public String getSfServer()
    {
        return sfServer;
    }

    public String getSfPassword()
    {
        return sfPassword;
    }

    private void printMembers(BuildListener listener, ArrayList<SMAMetadata> members)
    {
        listener.getLogger().println("[SMA] - Deploying the following metadata:");
        for(SMAMetadata member : members)
        {
            if (member.isValid())
            {
                listener.getLogger().println("\t" + member.getFullName());
            }
        }
    }

    @Override
    public DescriptorImpl getDescriptor()
    {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Internal class that directly interfaces with Jenkins
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
    {
        private String runTestRegex = ".*[T|t]est.*";
        private String maxPoll = "20";
        private String pollWait = "30000";

        public DescriptorImpl()
        {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass)
        {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        public String getDisplayName()
        {
            return "Salesforce Migration Assistant";
        }

        public String getRunTestRegex()
        {
            return runTestRegex;
        }

        public String getMaxPoll()
        {
            return maxPoll;
        }

        public String getPollWait()
        {
            return pollWait;
        }

        public ListBoxModel doFillSfServerItems()
        {
            return new ListBoxModel(
                    new ListBoxModel.Option("Production (https://login.salesforce.com)", "https://login.salesforce.com"),
                    new ListBoxModel.Option("Sandbox (https://test.salesforce.com)", "https://test.salesforce.com")
            );
        }

        public boolean configure(StaplerRequest request, JSONObject formData) throws FormException
        {
            runTestRegex = formData.getString("runTestRegex");
            maxPoll = formData.getString("maxPoll");
            pollWait = formData.getString("pollWait");

            save();
            return false;
        }
    }
}

