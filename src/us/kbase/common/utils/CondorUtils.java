package us.kbase.common.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import us.kbase.auth.AuthToken;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CondorUtils {


    public static final List<String> special_cases = Arrays.asList("request_cpus", "request_disk", "request_memory", "docker_job_timeout");

    /**
     * Create a condor submit file for Submitted Jobs
     *
     * @param ujsJobId                    The UJS job id
     * @param token                       The token of the user of the submitted job
     * @param adminToken                  The admin token used for bind mounts, stored in configs
     * @param clientGroupsAndRequirements The AWE Client Group and an optional requirements statement, csv format
     * @param kbaseEndpoint               The URL of the NJS Server
     * @param baseDir                     The Directory for the job to run in /mnt/awe/condor/username/
     * @return The generated condor submit file
     * @throws IOException
     */
    private static File createCondorSubmitFile(String ujsJobId, AuthToken token, AuthToken adminToken, String clientGroupsAndRequirements, String kbaseEndpoint, String baseDir, HashMap<String, String> optClassAds) throws IOException {
        HashMap<String, String> reqs = clientGroupsAndRequirements(clientGroupsAndRequirements);
        String clientGroups = reqs.get("client_group");
        String jobDir = baseDir + "/" + ujsJobId;

        //TODO Get from config / env
        File logdir = new File(String.format("/mnt/awe/condor/submit/logs/%s/%s", token.getUserName(), ujsJobId));
        logdir.mkdirs();

        HashMap<String, String> envVariables = new HashMap<>();
        String requestCpus = "request_cpus = 4";
        String requestMemory = "request_memory = 25000";
        String requestMemoryLowerBound = "25000";
        String requestDisk = "request_disk = 100GB";

        //Default at MB for now
        String requestCpusKey = "request_cpus";
        if (reqs.containsKey(requestCpusKey)) {
            requestCpus = String.format("%s = %s", requestCpusKey, reqs.get(requestCpusKey));
            envVariables.put(requestCpusKey, reqs.get(requestCpusKey));
        }
        String requestMemoryKey = "request_memory";
        if (reqs.containsKey(requestMemoryKey)) {
            requestMemoryLowerBound = reqs.get(requestMemoryKey);
            requestMemory = String.format("%s = %sMB", requestMemoryKey, reqs.get(requestMemoryKey));
            envVariables.put(requestMemoryKey, reqs.get(requestMemoryKey));
        }
        String requestDiskKey = "request_disk";
        if (reqs.containsKey(requestDiskKey)) {
            requestDisk = String.format("%s = %sMB", requestDiskKey, reqs.get(requestDiskKey));
        }
        String dockerJobTimeout = "docker_job_timeout";
        envVariables.put("DOCKER_JOB_TIMEOUT", reqs.getOrDefault(dockerJobTimeout, "604800"));  //7 Days

        envVariables.put("KB_AUTH_TOKEN", token.getToken());
        envVariables.put("KB_ADMIN_AUTH_TOKEN", adminToken.getToken());
        envVariables.put("AWE_CLIENTGROUP", clientGroups);
        envVariables.put("BASE_DIR", baseDir);
        envVariables.put("UJS_JOB_ID", ujsJobId);
        envVariables.put("JOB_DIR", jobDir);
        envVariables.put("CONDOR_ID", "$(Cluster).$(Process)");

        List<String> environment = new ArrayList<String>();
        for (String key : envVariables.keySet()) {
            environment.add(String.format("%s=%s", key, envVariables.get(key)));
        }

        String executable = "/kb/deployment/misc/sdklocalmethodrunner.sh";
        String[] args = {ujsJobId, kbaseEndpoint};
        String arguments = String.join(" ", args);
        List<String> csf = new ArrayList<String>();
        csf.add("universe = vanilla");
        csf.add(String.format("+AccountingGroup  = \"%s\"", token.getUserName()));

        //Concurrency Limits based on default
        csf.add(String.format("Concurrency_Limits = %s", token.getUserName()));

        csf.add("+Owner = \"condor_pool\"");
        csf.add("universe = vanilla");
        csf.add("executable = " + executable);
        csf.add("ShouldTransferFiles = YES");

        //TODO maybe add ON_EXIT_OR_EVICT
        csf.add("when_to_transfer_output = ON_EXIT");

        String[] transfer_files = new String[]{"/kb/deployment/lib/NJSWrapper-all.jar",
                "/kb/deployment/bin/mydocker",
                "/kb/deployment/misc/pre.sh",
                "/kb/deployment/misc/post.sh",
        };

        csf.add(String.format("transfer_input_files = %s", String.join(",", transfer_files)));
        csf.add(requestCpus);
        //Dynamically Request memory 1.5X
        requestMemory = String.format("request_memory = ifthenelse(MemoryUsage =!= undefined, MAX({MemoryUsage * 3/2, %s}), %s)",
                requestMemoryLowerBound, requestMemoryLowerBound);
        csf.add(requestMemory);
        csf.add(requestDisk);


        /* Condor log relative to submit machine, except when spooled.
           If this dir doesn't exist bad things will happen
           Output and Error are relative to the execute dir on the execute host
         */

//         csf.add(String.format("log = %s/%s.log", logdir, ujsJobId));
//         csf.add(String.format("output = %s/%s.out", logdir, ujsJobId));
//         csf.add(String.format("error = %s/%s.err", logdir, ujsJobId));

        csf.add("getenv = false");
        // Fix for rescheduling running jobs.
        csf.add("on_exit_hold = ExitCode =!= 0");
        // Allow up to 24 hours of no response from job
        csf.add("JobLeaseDuration = 86400");
        // 7 day max job retirement time for condor_drain
        csf.add("MaxJobRetirementTime = 604800");
        csf.add("requirements = " + reqs.get("requirements_statement"));

        csf.add("CurrentWallTime = ifthenelse(JobStatus==2,CurrentTime-EnteredCurrentStatus,0)");
        csf.add("Periodic_Remove = ( RemoteWallClockTime > 604800 )");
        //Periodic Remove
        csf.add("SUBMIT_ATTRS = $(SUBMIT_ATTRS) CurrentWallTime");

        //Create a default list of submitters in condor and

        csf.add(String.format("environment = \"%s\"", String.join(" ", environment)));

        /**
         TODO Use ENV as Variable
         csf.add(String.format("+PreEnvironment = \"%s\"", String.join(" ", environment)));
         csf.add(String.format("+PostEnvironment = \"%s\"", String.join(" ", environment)));
         csf.add("+PreCmd = \"pre.sh\"");
         csf.add("+PostCmd = \"post.sh\"");
         **/


        csf.add("arguments = " + arguments);
        csf.add("batch_name = " + ujsJobId);
        if (optClassAds != null) {
            for (Map.Entry<String, String> pair : optClassAds.entrySet()) {
                csf.add(String.format("+%s = \"%s\"", pair.getKey(), pair.getValue()));
            }
        }
        csf.add("queue 1");


        File submitFile = new File(String.format("%s/%s.sub", logdir, ujsJobId));
        System.out.println("ABOUT TO PRINT OUT:" + submitFile);
        FileUtils.writeLines(submitFile, "UTF-8", csf);
        submitFile.setExecutable(true);
        return submitFile;
    }

    /**
     * Run the condor command and return the results, minding the TimeOut .
     *
     * @param condorCommand command to run
     * @throws IOException
     * @returnCondorResponse with STDIN and STDOUT
     */
    public static CondorResponse runProcess(String[] condorCommand) throws IOException {
        //TODO DELETE PRINT STATEMENT
        System.out.println("Running command: [" + String.join(" ", condorCommand) + "]");
        final Process process = Runtime.getRuntime().exec(condorCommand);

        List<String> stdOutMessage = IOUtils.readLines(process.getInputStream(), "UTF-8");
        List<String> stdErrMessage = IOUtils.readLines(process.getErrorStream(), "UTF-8");

        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        CondorResponse failure = new CondorResponse(stdOutMessage, stdErrMessage, false);
        if (process.isAlive()) {
            System.out.println("Error: Command didn't finish" + String.join(" ", condorCommand) + "]");
            return failure;
        }
        if (process.exitValue() != 0) {
            System.err.println("STDOUT:");
            for (String s : stdOutMessage) {
                System.err.println(s);
            }
            System.out.println("STDERR:");
            for (String s : stdOutMessage) {
                System.err.println(stdErrMessage);
            }
            return failure;
        }
        return new CondorResponse(stdOutMessage, stdErrMessage, true);
    }


    /**
     * Parse out requirements frm client groups
     * The format is clientGroup, and then a series of requirements
     * The requirements can either be "Attribute=Value", or "Attribute", or a special case
     * There are three special cases, "request_cpus", "request_disk", "request_memory"
     * These cases are not part of the requirements statement, but condor can use them
     * <p>
     * An example is "ClientGroupA,request_cpus=4,request_disk=1GB,request_memory=2048kb,color=blue,LowMemory"
     * or
     * "ClientGroupB,Fast,Budget,HighMemory,LuckyNumber=12"
     *
     * @param clientGroupsAndRequirements
     * @return a map of client_groups, resource_requirements, and classAds
     */
    public static HashMap<String, String> clientGroupsAndRequirements(String clientGroupsAndRequirements) {

        //Here is a working example of what might come in the clientGroupsAndRequirements
        //clientGroupsAndRequirements = "njs,request_memory=500,request_cpus=1,request_disk=10";

        String[] items = clientGroupsAndRequirements.split(",");
        String clientGroup = cleanCondorInputs(items[0]);
        HashMap<String, String> reqs = new HashMap<String, String>();

        reqs.put("client_group", clientGroup);

        List<String> requirementsStatement = new ArrayList<String>();
            requirementsStatement.add(String.format("regexp(\"%s\",CLIENTGROUP)", clientGroup));


        for (int i = 1; i < items.length; i++) {
            String condorInput = cleanCondorInputs(items[i]);
            if (condorInput.contains("=")) {
                String[] keyValue = condorInput.split("=");
                if (special_cases.contains(keyValue[0])) {
                    reqs.put(keyValue[0], keyValue[1]);
                } else {
                    requirementsStatement.add(String.format("(%s == \"%s\")", keyValue[0], keyValue[1]));
                }
            } else {
                requirementsStatement.add(String.format("(%s)", condorInput));
            }
        }
        reqs.put("requirements_statement", "(" + String.join(" && ", requirementsStatement) + ")");
        return reqs;
    }


    /**
     * Remove spaces and maybe perform other logic
     *
     * @param clientGroups to run the job with
     * @return String modified client groups
     */
    public static String clientGroupsToRequirements(String clientGroups) {
        List<String> requirementsStatement = new ArrayList<String>();
        for (String cg : clientGroups.split(",")) {
            cg = cleanCondorInputs(cg);
            requirementsStatement.add(String.format("(CLIENTGROUP == \"%s\")", cg));
        }
        return "(" + String.join(" || ", requirementsStatement) + ")";
    }

    /**
     * Remove space, remove single quote, and remove double quotes.
     *
     * @param input
     * @return cleaned string
     */
    public static String cleanCondorInputs(String input) {
        return input.replaceAll("[^0-9A-Za-z=_]", "");
    }

    /**
     * Call condor_submit with the ujsJobId as batch job name
     *
     * @param ujsJobId      The UJS job id
     * @param token         The token of the user of the submitted job
     * @param clientGroups  The AWE Client Group
     * @param kbaseEndpoint The URL of the NJS Server
     * @param baseDir       The Directory for the job to run in /mnt/awe/condor/username/
     * @param adminToken    The admin token used for bind mounts, stored in configs
     * @return String condor job id Range
     * @throws Exception
     */
    public static String submitToCondorCLI(String ujsJobId, AuthToken token, String clientGroups, String kbaseEndpoint, String baseDir, HashMap<String, String> optClassAds, AuthToken adminToken) throws Exception {
        File condorSubmitFile = createCondorSubmitFile(ujsJobId, token, adminToken, clientGroups, kbaseEndpoint, baseDir, optClassAds);
        String[] cmdScript = {"condor_submit", "-terse", "-spool" , condorSubmitFile.getAbsolutePath()};
        String jobID = null;
        int retries = 10;

        String stderr = null;
        while (jobID == null && retries > 0) {
            CondorResponse r = runProcess(cmdScript);
            if (r.success)
                jobID = r.stdout.get(0);

            stderr = String.join(", ", r.stderr);
            retries--;
        }
        if (jobID == null) {
            throw new IllegalStateException("Error running condorCommand: \n" + String.join(" ", cmdScript) + "\n" + stderr + "\n");
        }
        if (!optClassAds.containsKey("debugMode")) {
            condorSubmitFile.delete();
        }
        return jobID;
    }


    /**
     * Call condor_q with the ujsJobId a string target to filter condor_q
     *
     * @param ujsJobId  to get job JobPrio for
     * @param attribute attribute to search condorQ for
     * @return String condor job attribute or NULL
     */
    public static String condorQ(String ujsJobId, String attribute) throws IOException, InterruptedException {
        int retries = 3;
        String result = null;
        String[] cmdScript = new String[]{"/kb/deployment/misc/condor_q.sh", ujsJobId, attribute};
        while (result == null && retries > 0) {
            Thread.sleep(5000);
            result = String.join("\n", runProcess(cmdScript).stdout);
            // convert JSON string to Map There has to be a better way than this
            if (result.contains(attribute)) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    List<Map<String, Object>> myObjects =
                            mapper.readValue(result, new TypeReference<List<Map<String, Object>>>() {
                            });
                    result = myObjects.get(0).get(attribute).toString();
                } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
                    result = null;
                }
            } else {
                result = null;
            }
            retries--;
        }
        return result;
    }

    /**
     * Useful for condor autoformat commands with two return columns
     */
    private static HashMap<String, String> autoFormatHelper(String[] cmdScript) throws Exception {
        List<String> processResult = runProcess(cmdScript).stdout;
        HashMap<String, String> JobStates = new HashMap<>();
        for (String line : processResult) {
            String[] idStatusLine = line.split(" ");
            JobStates.put(idStatusLine[0], idStatusLine[1]);
        }
        return JobStates;
    }


    /**
     * Get a list of running/idle/held jobs and their statuses
     *
     * @return A List of job IDS and their respective statuses.
     * @throws Exception
     */
    public static HashMap<String, String> getIdleOrRunningOrHeldJobs() throws Exception {
        //NEVER EVER USE QUOTES OR ESCAPED QUOTES FOR CONDOR COMMANDS! THEY DON'T WORK!
        String[] cmdScript = new String[]{"condor_q", "-constraint", "JobStatus == 0 || JobStatus == 1 || JobStatus == 2 || JobStatus == 5", "-af", "JobBatchName", "JobStatus"};
        return autoFormatHelper(cmdScript);
    }

    /**
     * Get a list of running/idle jobs and their statuses
     *
     * @return A List of job IDS and their respective statuses.
     * @throws Exception
     */
    public static HashMap<String, String> getIdleAndRunningJobs() throws Exception {
        //NEVER EVER USE QUOTES OR ESCAPED QUOTES FOR CONDOR COMMANDS! THEY DON'T WORK!
        String[] cmdScript = new String[]{"condor_q", "-constraint", "JobStatus == 1 || JobStatus == 2", "-af", "JobBatchName", "JobStatus"};
        return autoFormatHelper(cmdScript);
    }

    /**
     * Get a list of jobs ids and job statuses for all jobs recorded in condor
     *
     * @return A List of job IDS and their respective statuses.
     * @throws Exception
     */
    public static HashMap<String, String> getAllJobStates() throws Exception {
        String[] cmdScript = new String[]{"condor_q", "-af", "JobBatchName", "JobStatus"};
        return autoFormatHelper(cmdScript);
    }


    /**
     * Get job state from condor_q with the LastJobStatus param
     *
     * @param ujsJobId ujsJobId to get job state for
     * @return String  condor job state or NULL
     */
    public static String getJobState(String ujsJobId) throws Exception {
        return condorQ(ujsJobId, "LastJobStatus");
    }

    /**
     * Get job priority from condor_q with the JobPrio param
     *
     * @param ujsJobId ujsJobId to get job JobPrio for
     * @return String  condor job priority or NULL
     */
    public static String getJobPriority(String ujsJobId) throws Exception {
        return condorQ(ujsJobId, "JobPrio");
    }

    /**
     * Remove condor jobs with a given batch name
     *
     * @param ujsJobID ujsJobId for the job batch name
     * @return Result of the condor_rm command
     */
    public static String condorRemoveJobRange(String ujsJobID) throws Exception {
        String[] cmdScript = new String[]{"/kb/deployment/misc/condor_rm.sh", ujsJobID};
        String processResult = runProcess(cmdScript).stdout.get(0);
        return processResult;
    }

    /**
     * Send a condor_rm "BatchName" and don't check to see it's result
     *
     * @param ujsJobID ujsJobId for the job batch name
     * @return Result of the condor_rm command
     */
    public static void condorRemoveJobRangeAsync(String ujsJobID) throws Exception {
        Runtime.getRuntime().exec(new String[]{"/kb/deployment/misc/condor_rm.sh", ujsJobID});
    }


} // class CondorUtils
