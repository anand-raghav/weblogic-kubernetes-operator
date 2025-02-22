// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLProtocolException;

import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1DeploymentStrategy;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1RollingUpdateDeployment;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.logging.LoggingFacade;

import static io.kubernetes.client.util.Yaml.dump;
import static oracle.weblogic.kubernetes.TestConstants.BASE_IMAGES_REPO_SECRET;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.createSecret;
import static oracle.weblogic.kubernetes.actions.TestActions.execCommand;
import static oracle.weblogic.kubernetes.actions.TestActions.listServices;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podReady;
import static oracle.weblogic.kubernetes.assertions.impl.Kubernetes.getPod;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.FileUtils.copyFileToPod;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createSecretForBaseImages;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Utility class to start DB service and RCU schema.
 */
public class DbUtils {

  private static final String CREATE_REPOSITORY_SCRIPT = "createRepository.sh";
  private static final String PASSWORD_FILE = "pwd.txt";
  private static final String RCUTYPE = "fmw";
  private static final String RCUPODNAME = "rcu";
  private static final String SYSPASSWORD = "Oradoc_db1";

  private static V1Service oracleDBService = null;
  private static V1Deployment oracleDbDepl = null;
  private static int suffixCount = 0;
  private static Map<String, String> dbMap = new HashMap<>();

  /**
   * Start Oracle DB instance, create rcu pod and load database schema in the specified namespace.
   *
   * @param dbImage image name of database
   * @param fmwImage image name of FMW
   * @param rcuSchemaPrefix rcu SchemaPrefix
   * @param dbNamespace namespace where DB and RCU schema are going to start
   * @param dbPort NodePort of DB
   * @param dbUrl URL of DB
   * @throws Exception if any error occurs when setting up RCU database
   */

  public static synchronized void setupDBandRCUschema(String dbImage, String fmwImage, String rcuSchemaPrefix,
       String dbNamespace, int dbPort, String dbUrl, int dbListenerPort) throws ApiException {
    LoggingFacade logger = getLogger();
    // create pull secrets when running in non Kind Kubernetes cluster
    // this secret is used only for non-kind cluster
    createSecretForBaseImages(dbNamespace);

    logger.info("Start Oracle DB with dbImage: {0}, dbPort: {1}, dbNamespace: {2}, dbListenerPort:{3}",
        dbImage, dbPort, dbNamespace, dbListenerPort);
    startOracleDB(dbImage, dbPort, dbNamespace, dbListenerPort);
    logger.info("Create RCU schema with fmwImage: {0}, rcuSchemaPrefix: {1}, dbUrl: {2}, "
        + " dbNamespace: {3}:", fmwImage, rcuSchemaPrefix, dbUrl, dbNamespace);
    createRcuSchema(fmwImage, rcuSchemaPrefix, dbUrl, dbNamespace);

  }

  /**
   * Start Oracle DB pod and service in the specified namespace.
   *
   * @param dbBaseImageName full image name for DB deployment
   * @param dbPort NodePort of DB
   * @param dbNamespace namespace where DB instance is going to start
   * @param dbListenerPort TCP listener port of DB
   */
  public static synchronized void startOracleDB(String dbBaseImageName, int dbPort, String dbNamespace,
      int dbListenerPort) throws ApiException {
    LoggingFacade logger = getLogger();
    Map labels = new HashMap<String, String>();
    labels.put("app", "database");

    Map limits = new HashMap<String, String>();
    limits.put("cpu", "2");
    limits.put("memory", "10Gi");
    limits.put("ephemeral-storage", "8Gi");
    Map requests = new HashMap<String, String>();
    requests.put("cpu", "500m");
    requests.put("ephemeral-storage", "8Gi");

    //create V1Service for Oracle DB
    oracleDBService = new V1Service()
        .apiVersion("v1")
        .kind("Service")
        .metadata(new V1ObjectMeta()
            .name("oracledb")
            .namespace(dbNamespace)
            .labels(labels))
        .spec(new V1ServiceSpec()
            .ports(Arrays.asList(
                new V1ServicePort()
                    .name("tns")
                    .port(dbListenerPort)
                    .protocol("TCP")
                    .targetPort(new IntOrString(1521))
                    .nodePort(dbPort)))
            .selector(labels)
            .sessionAffinity("None")
            .type("LoadBalancer"));

    logger.info("Create service for Oracle DB service in namespace {0}, dbListenerPort: {1}", dbNamespace,
        dbListenerPort);
    boolean serviceCreated = assertDoesNotThrow(() -> Kubernetes.createService(oracleDBService),
        String.format("Create service failed with ApiException for oracleDBService in namespace %s",
            dbNamespace));
    assertTrue(serviceCreated, String.format(
        "Create service failed for OracleDbService in namespace %s dbListenerPort %s ", dbNamespace, dbListenerPort));

    //create V1Deployment for Oracle DB
    logger.info("Configure V1Deployment in namespace {0} using image {1} dbListenerPort {2}", dbNamespace,
        dbBaseImageName, dbListenerPort);
    oracleDbDepl = new V1Deployment()
        .apiVersion("apps/v1")
        .kind("Deployment")
        .metadata(new V1ObjectMeta()
            .name("oracledb")
            .namespace(dbNamespace)
            .labels(labels))
        .spec(new V1DeploymentSpec()
            .replicas(1)
            .selector(new V1LabelSelector()
                .matchLabels(labels))
            .strategy(new V1DeploymentStrategy()
                 .rollingUpdate(new V1RollingUpdateDeployment()
                     .maxSurge(new IntOrString(1))
                     .maxUnavailable(new IntOrString(1)))
                 .type("RollingUpdate"))
            .template(new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta()
                    .labels(labels))
                .spec(new V1PodSpec()
                    .containers(Arrays.asList(
                        new V1Container()
                            .addEnvItem(new V1EnvVar().name("DB_SID").value("devcdb"))
                            .addEnvItem(new V1EnvVar().name("DB_PDB").value("devpdb"))
                            .addEnvItem(new V1EnvVar().name("DB_DOMAIN").value("k8s"))
                            .addEnvItem(new V1EnvVar().name("DB_BUNDLE").value("basic"))
                            .image(dbBaseImageName)
                            .imagePullPolicy("IfNotPresent")
                            .name("oracledb")
                            .ports(Arrays.asList(
                                new V1ContainerPort()
                                .containerPort(dbListenerPort)
                                .name("tns")
                                .protocol("TCP")
                                .hostPort(dbListenerPort)))
                            .resources(new V1ResourceRequirements()
                                .limits(limits)
                                .requests(requests))
                            .terminationMessagePath("/dev/termination-log")
                            .terminationMessagePolicy("File")))
                    .dnsPolicy("ClusterFirst")
                    .restartPolicy("Always")
                    .schedulerName("default-scheduler")
                    .terminationGracePeriodSeconds(30L)
                    .imagePullSecrets(Arrays.asList(
                        new V1LocalObjectReference()
                            .name(BASE_IMAGES_REPO_SECRET))))));

    logger.info("Create deployment for Oracle DB in namespace {0} dbListenerPost {1}",
        dbNamespace, dbListenerPort);
    boolean deploymentCreated = assertDoesNotThrow(() -> Kubernetes.createDeployment(oracleDbDepl),
        String.format("Create deployment failed with ApiException for Oracle DB in namespace %s",
            dbNamespace));
    assertTrue(deploymentCreated, String.format(
        "Create deployment failed for oracleDbDepl in namespace %s  dbListenerPort %s",
        dbNamespace, dbListenerPort));

    // sleep for a while to make sure the DB pod is created
    try {
      Thread.sleep(2 * 1000);
    } catch (InterruptedException ie) {
        // ignore
    }

    // wait for the Oracle DB pod to be ready
    String dbPodName = assertDoesNotThrow(() -> getPodNameOfDb(dbNamespace),
        String.format("Get Oracle DB pod name failed with ApiException for oracleDBService in namespace %s",
            dbNamespace));
    logger.info("Wait for the oracle Db pod: {0} ready in namespace {1}", dbPodName, dbNamespace);
    testUntil(
        assertDoesNotThrow(() -> podIsReady(dbNamespace, "app=database", dbPodName),
          "oracleDBService podReady failed with ApiException"),
        logger,
        "Oracle DB to be ready in namespace {0}",
        dbNamespace);

    // check if DB is ready to be used by searching pod log
    logger.info("Check for DB pod {0} log contains ready message in namespace {1}",
        dbPodName, dbNamespace);
    String msg = "The database is ready for use";
    checkDbReady(msg, dbPodName, dbNamespace);

    dbMap.put(dbNamespace, dbPodName);
  }

  /**
   * Create a RCU schema in the namespace.
   *
   * @param fmwBaseImageName the FMW image name
   * @param rcuPrefix prefix of RCU schema
   * @param dbUrl URL of DB
   * @param dbNamespace namespace of DB where RCU is
   * @throws ApiException when create RCU pod fails
   */
  public static synchronized void createRcuSchema(String fmwBaseImageName, String rcuPrefix, String dbUrl,
      String dbNamespace) throws ApiException {
    LoggingFacade logger = getLogger();
    logger.info("Create RCU pod for for namespace: {0}, RCU prefix: {1}, "
         + "dbUrl: {2},  fmwImage: {3} ", dbNamespace, rcuPrefix, dbUrl, fmwBaseImageName);
    assertDoesNotThrow(() -> createRcuPod(fmwBaseImageName, dbUrl, dbNamespace),
        String.format("Creating RCU pod failed with ApiException for image: %s, rcuPrefix: %s, dbUrl: %s, "
                + "in namespace: %s", fmwBaseImageName, rcuPrefix, dbUrl, dbNamespace));

    logger.info("createRcuRepository for dbNamespace: {0}, dbUrl: {1}, RCU prefix: {2} ",
            dbNamespace, dbUrl, rcuPrefix);
    assertTrue(assertDoesNotThrow(
        () -> createRcuRepository(dbNamespace, dbUrl, rcuPrefix),
        String.format("createRcuRepository failed for dbNamespace: %s, dbUrl: %s, rcuPrefix: %s",
            dbNamespace, dbUrl, rcuPrefix)));
  }

  /**
   * Create a RCU where createRepository script runs.
   *
   * @param fmwBaseImageName the FMW image name
   * @param dbUrl URL of DB
   * @param dbNamespace namespace of DB where RCU is
   * @throws ApiException when create RCU pod fails
   */
  public static V1Pod createRcuPod(String fmwBaseImageName, String dbUrl, String dbNamespace)
      throws ApiException {
    LoggingFacade logger = getLogger();

    Map labels = new HashMap<String, String>();
    labels.put("ruc", "rcu");

    V1Pod podBody = new V1Pod()
        .apiVersion("v1")
        .kind("Pod")
        .metadata(new V1ObjectMeta()
            .name(RCUPODNAME)
            .namespace(dbNamespace)
            .labels(labels))
        .spec(new V1PodSpec()
            .containers(Arrays.asList(
                new V1Container()
                    .name("rcu")
                    .image(fmwBaseImageName)
                    .imagePullPolicy("IfNotPresent")
                    .addArgsItem("sleep")
                    .addArgsItem("infinity")))
            .imagePullSecrets(Arrays.asList(
                        new V1LocalObjectReference()
                            .name(BASE_IMAGES_REPO_SECRET))));  // this secret is used only for non-kind cluster

    V1Pod pvPod = Kubernetes.createPod(dbNamespace, podBody);

    testUntil(
        podReady(RCUPODNAME, null, dbNamespace),
        logger,
        "{0} to be ready in namespace {1}",
        RCUPODNAME,
        dbNamespace);

    return pvPod;
  }

  /**
   * Checks if a pod is ready in a given namespace.
   *
   * @param namespace in which to check if the pod is ready
   * @param labelSelector the label the pod is decorated with
   * @param podName name of the pod to check for
   * @return true if the pod is in the ready condition, false otherwise
   * @throws ApiException if Kubernetes client API call fails
   */
  public static boolean isPodReady(String namespace, String labelSelector, String podName) throws ApiException {
    LoggingFacade logger = getLogger();
    boolean status = false;
    V1Pod pod = getPod(namespace, labelSelector, podName);
    if (pod != null) {

      // get the podCondition with the 'Ready' type field
      V1PodCondition v1PodReadyCondition = pod.getStatus().getConditions().stream()
          .filter(v1PodCondition -> "Ready".equals(v1PodCondition.getType()))
          .findAny()
          .orElse(null);

      if (v1PodReadyCondition != null) {
        status = v1PodReadyCondition.getStatus().equalsIgnoreCase("true");
        if (status) {
          logger.info("Pod {0} is READY in namespace {1}", podName, namespace);
        }
      }
    } else {
      logger.info("Pod {0} does not exist in namespace {1}", podName, namespace);
    }
    return status;
  }

  /**
   * Check if Pod is ready.
   *
   * @param namespace in which to check if the pod is ready
   * @param labelSelector the label the pod is decorated with
   * @param podName name of the pod to check for
   * @return true if the pod is in the ready condition, false otherwise
   * @throws ApiException if Kubernetes client API call fails
   */
  public static Callable<Boolean> podIsReady(String namespace,
                                             String labelSelector,
                                             String podName) throws ApiException {
    return () -> isPodReady(namespace, labelSelector, podName);
  }

  private static boolean createRcuRepository(String dbNamespace, String dbUrl,
                                         String rcuSchemaPrefix)
      throws ApiException, IOException {
    LoggingFacade logger = getLogger();
    // copy the script and helper files into the RCU pod
    Path createRepositoryScript = Paths.get(RESOURCE_DIR, "bash-scripts", CREATE_REPOSITORY_SCRIPT);
    Path passwordFile = Paths.get(RESOURCE_DIR, "helper-files", PASSWORD_FILE);
    Path podCreateRepositoryScript = Paths.get("/u01/oracle", CREATE_REPOSITORY_SCRIPT);
    Path podPasswordFile = Paths.get("/u01/oracle", PASSWORD_FILE);

    logger.info("source file is: {0}, target file is: {1}", createRepositoryScript, podCreateRepositoryScript);
    FileUtils.copyFileToPod(dbNamespace, RCUPODNAME, null, createRepositoryScript, podCreateRepositoryScript);
    logger.info("source file is: {0}, target file is: {1}", passwordFile, podPasswordFile);
    FileUtils.copyFileToPod(dbNamespace, RCUPODNAME, null, passwordFile, podPasswordFile);

    String createRepository = "/u01/oracle/createRepository.sh";
    logger.info("Running the createRepository command: {0},  dbUrl: {1}, rcuSchemaPrefix: {2}, RCU type: {3}, "
        + "SYSPASSWORD: {4} ", createRepository, dbUrl, rcuSchemaPrefix, RCUTYPE, SYSPASSWORD);

    /* TODO The original code without encountering SSLProtocolException. Rollback to this oneWhen the bug is fixed.
    ExecResult execResult = assertDoesNotThrow(
        () -> execCommand(dbNamespace, RCUPODNAME,
            null, true, "/bin/bash", createRepository, dbUrl, rcuSchemaPrefix,
            RCUTYPE, SYSPASSWORD));
    logger.info("Inside RCU pod command createRepository return value: {0}", execResult.exitValue());
    if (execResult.exitValue() != 0) {
      logger.info("Inside RCU pod command createRepository return error {0}", execResult.stderr());
      return false;
    */
    try {
      execCommand(dbNamespace, RCUPODNAME,
          null, true, "/bin/bash", createRepository, dbUrl, rcuSchemaPrefix,
          RCUTYPE, SYSPASSWORD);

    } catch (SSLProtocolException e) {
      /* TODO For Api 8.0.2 it looks that there is a bug on the web socket code or a timing bug
      where it doesn't properly handle closing a socket that has already been closed by the other
      side. Sometimes on remote Jenkins cluster 10 when RCU creation is completed java.net.ssl.SSLProtocolException
      is thrown. Ignore it for now */
      return true;
    } catch (InterruptedException e) {
      return false;
    } catch (ApiException e) {
      return false;
    }

    return true;
  }

  /**
   * Get database pod name.
   * @param dbNamespace namespace where database exists
   * @return pod name of database
   * @throws ApiException if Kubernetes client API call fails
   */
  public static String getPodNameOfDb(String dbNamespace) throws ApiException {

    V1PodList  pod = null;
    pod = Kubernetes.listPods(dbNamespace, null);

    String podName = null;

    //There is only one pod in the given DB namespace
    if (pod != null) {
      podName = pod.getItems().get(0).getMetadata().getName();
    }

    return podName;
  }

  /**
   * Check if the pod log contains the certain text.
   * @param matchStr text to be searched in the log
   * @param podName the name of the pod
   * @param namespace namespace where pod exists
   * @return true if the text exists in the log otherwise false
   * @throws ApiException if Kubernetes client API call fails
   */
  public static boolean checkPodLogContains(String matchStr, String podName, String namespace)
      throws ApiException {

    return Kubernetes.getPodLog(podName,namespace,null).contains(matchStr);

  }

  private static Callable<Boolean> podLogContains(String matchStr, String podName, String dbNamespace)
      throws ApiException {
    return () -> checkPodLogContains(matchStr, podName, dbNamespace);
  }

  /**
   * Check if the database service is ready.
   * @param matchStr text to be searched in the log
   * @param podName the name of the pod
   * @param dbNamespace database namespace where pod exists
   */
  public static void checkDbReady(String matchStr, String podName, String dbNamespace) {
    LoggingFacade logger = getLogger();
    testUntil(
        assertDoesNotThrow(() -> podLogContains(matchStr, podName, dbNamespace),
          String.format("podLogContains failed with ApiException for pod %s in namespace %s", podName, dbNamespace)),
        logger,
        "pod {0} log contain message {1} in namespace {2}",
        podName,
        matchStr,
        dbNamespace);
  }

  /**
   * Delete all db in the given namespace, if any exists.
   *
   * @param dbNamespace name of the namespace
   */
  public static void deleteDb(String dbNamespace) {
    LoggingFacade logger = getLogger();

    try {
      // delete db resources in dbNamespace
      new Command()
          .withParams(new CommandParams()
              .command("kubectl delete all --all -n " + dbNamespace + " --ignore-not-found"))
          .execute();
    } catch (Exception ex) {
      logger.severe(ex.getMessage());
      logger.severe("Failed to delete db or the {0} is not a db namespace", dbNamespace);
    }
  }

  /**
   * Returns a DB NodePort value .
   *
   * @param dbNamespace database namespace where pod exists
   * @param dbName database name
   * @return DB NodePort value
   */
  public static Integer getDBNodePort(String dbNamespace, String dbName) {
    LoggingFacade logger = getLogger();
    logger.info(dump(Kubernetes.listServices(dbNamespace)));
    List<V1Service> services = listServices(dbNamespace).getItems();
    for (V1Service service : services) {
      if (service.getMetadata().getName().startsWith(dbName)) {
        return service.getSpec().getPorts().get(0).getNodePort();
      }
    }
    return -1;
  }

  /**
   * Create leasing Table (ACTIVE) on an Oracle DB Instance.
   * Uses the WebLogic utility utils.Schema to add the table
   * So the command MUST be run inside a Weblogic Server pod
   *
   * @param podName the pod name
   * @param namespace where pod exists
   * @param dbNodePort NodePort for the Oracle DB service
   */
  public static void createLeasingTable(String podName, String namespace, int dbNodePort) {
    Path ddlFile = Paths.get(WORK_DIR + "/leasing.ddl");
    String ddlString = "DROP TABLE ACTIVE;\n"
        + "CREATE TABLE ACTIVE (\n"
        + "  SERVER VARCHAR2(255) NOT NULL,\n"
        + "  INSTANCE VARCHAR2(255) NOT NULL,\n"
        + "  DOMAINNAME VARCHAR2(255) NOT NULL,\n"
        + "  CLUSTERNAME VARCHAR2(255) NOT NULL,\n"
        + "  TIMEOUT DATE,\n"
        + "  PRIMARY KEY (SERVER, DOMAINNAME, CLUSTERNAME)\n"
        + ");\n";

    assertDoesNotThrow(() -> Files.write(ddlFile, ddlString.getBytes()));
    String destLocation = "/u01/leasing.ddl";
    assertDoesNotThrow(() -> copyFileToPod(namespace,
             podName, "",
             Paths.get(WORK_DIR, "leasing.ddl"),
             Paths.get(destLocation)));

    String cpUrl = "jdbc:oracle:thin:@//" + K8S_NODEPORT_HOST + ":"
                         + dbNodePort + "/devpdb.k8s";
    String jarLocation = "/u01/oracle/wlserver/server/lib/weblogic.jar";
    StringBuffer ecmd = new StringBuffer("java -cp ");
    ecmd.append(jarLocation);
    ecmd.append(" utils.Schema ");
    ecmd.append(cpUrl);
    ecmd.append(" oracle.jdbc.OracleDriver");
    ecmd.append(" -verbose ");
    ecmd.append(" -u \"sys as sysdba\"");
    ecmd.append(" -p Oradoc_db1");
    ecmd.append(" /u01/leasing.ddl");
    ExecResult execResult = assertDoesNotThrow(
        () -> execCommand(namespace, podName,
            null, true, "/bin/sh", "-c", ecmd.toString()));
    assertTrue(execResult.exitValue() == 0, "Could not create the Leasing Table");
  }

  /**
   * Update RCU schema password on an Oracle DB instance.
   *
   * @param dbNamespace namespace where DB instance started
   * @param rcuprefix prefix of RCU schema
   * @param rcupasswd RCU schema password that is going to change to
   */
  public static void updateRcuPassword(String dbNamespace, String rcuprefix, String rcupasswd) {

    Path updateScript = Paths.get(WORK_DIR + "/update.sql");
    String updateString = "alter user " + rcuprefix + "_MDS " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_IAU_VIEWER " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_WLS " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_OPSS " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_WLS_RUNTIME " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_IAU_APPEND " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_STB " + "identified by " + rcupasswd + ";\n"
        +  "alter user " + rcuprefix + "_IAU " + "identified by " + rcupasswd + ";\n"
        +  "commit;\n";
    getLogger().info("updateString is: \n" + updateString);
    assertDoesNotThrow(() -> Files.write(updateScript, updateString.getBytes()));

    //get dbPodName for the specified dbNamespace
    String dbPodName = dbMap.containsKey(dbNamespace) ? dbMap.get(dbNamespace) : null;
    assertNotNull(dbPodName, "Failed to get dbPodName");

    String updateLocation = "/u01/update.sql";
    getLogger().info("Is going to update RCU schema password for dbPod: {0} in namespace: {1} using "
        + "destLocation {2}", dbPodName, dbNamespace, updateLocation);
    assertDoesNotThrow(() -> copyFileToPod(dbNamespace,
             dbPodName, "",
             Paths.get(WORK_DIR, "update.sql"),
             Paths.get(updateLocation)));

    Path sqlplusScript = Paths.get(WORK_DIR + "/sqlplus.sh");
    String sqlplusString = "#!/bin/bash\n"
        + "$ORACLE_HOME/bin/sqlplus sys/Oradoc_db1@DEVPDB as sysdba < /u01/update.sql";
    getLogger().info("sqlCmd is: \n" + sqlplusString);
    assertDoesNotThrow(() -> Files.write(sqlplusScript, sqlplusString.getBytes()));

    String sqlplusLocation = "/u01/sqlplus.sh";
    getLogger().info("Is going to update RCU schema password for dbPod: {0} in namespace: {1} using"
        + "sqlplusLocation {2}", dbPodName, dbNamespace, sqlplusLocation);
    assertDoesNotThrow(() -> copyFileToPod(dbNamespace,
             dbPodName, "",
             Paths.get(WORK_DIR, "sqlplus.sh"),
             Paths.get(sqlplusLocation)));

    // change file permissions
    ExecResult execResult = assertDoesNotThrow(() -> execCommand(dbNamespace, dbPodName, null,
        true, "/bin/sh", "-c", "chmod +x " + sqlplusLocation),
        String.format("Failed to change permissions for file %s in pod %s", sqlplusLocation, dbPodName));
    assertTrue(execResult.exitValue() == 0,
        String.format("Failed to change file %s permissions, stderr %s stdout %s", sqlplusLocation,
            execResult.stderr(), execResult.stdout()));
    getLogger().info("File permissions changed inside pod");

    String cmd = "source /home/oracle/.bashrc; /u01/sqlplus.sh";
    getLogger().info("Command to run inside DB pod", cmd);
    execResult = assertDoesNotThrow(
        () -> execCommand(dbNamespace, dbPodName,
            null, true, "bin/bash", "-c", cmd));
    assertTrue(execResult.exitValue() == 0, "Could not update the RCU schema password");

  }

  /**
   * Create a RCU secret with username, password and sys_username, sys_password in the specified namespace.
   *
   * @param secretName secret name to create
   * @param namespace namespace in which the secret will be created
   * @param username RCU schema username
   * @param password RCU schema passowrd
   * @param sysUsername DB sys username
   * @param sysPassword DB sys password
   */
  public static void createRcuSecretWithUsernamePassword(String secretName, String namespace,
                                                         String username, String password,
                                                         String sysUsername, String sysPassword) {
    Map<String, String> secretMap = new HashMap<>();
    secretMap.put("username", username);
    secretMap.put("password", password);
    secretMap.put("sys_username", sysUsername);
    secretMap.put("sys_password", sysPassword);

    boolean secretCreated = assertDoesNotThrow(() -> createSecret(new V1Secret()
        .metadata(new V1ObjectMeta()
            .name(secretName)
            .namespace(namespace))
        .stringData(secretMap)), "Create secret failed with ApiException");
    assertTrue(secretCreated, String.format("create secret failed for %s", secretName));
  }

  /**
   * Create a RcuAccess secret with RCU schema prefix, RCU schema password and RCU database connection string in the
   * specified namespace.
   *
   * @param secretName secret name to create
   * @param namespace namespace in which the secret will be created
   * @param rcuPrefix  RCU schema prefix
   * @param password RCU schema passoword
   * @param rcuDbConnString RCU database connection string
   */
  public static void createRcuAccessSecret(String secretName, String namespace,
                                           String rcuPrefix, String password, String rcuDbConnString) {
    Map<String, String> secretMap = new HashMap<>();
    secretMap.put("rcu_db_conn_string", rcuDbConnString);
    secretMap.put("rcu_prefix", rcuPrefix);
    secretMap.put("rcu_schema_password", password);

    getLogger().info("Create RcuAccessSecret: {0} in namespace: {1}, with rcuPrefix {2}, password {3}, "
        + "rcuDbConnString {4} ", secretName, namespace, rcuPrefix, password, rcuDbConnString);
    boolean secretCreated = assertDoesNotThrow(() -> createSecret(new V1Secret()
        .metadata(new V1ObjectMeta()
            .name(secretName)
            .namespace(namespace))
        .stringData(secretMap)), "Create secret failed with ApiException");
    assertTrue(secretCreated, String.format("create secret failed for %s", secretName));
  }

  /**
   * Update a RcuAccess secret with RCU schema prefix, RCU schema password and RCU database connection string in the
   * specified namespace.
   *
   * @param secretName secret name to update
   * @param namespace namespace in which the secret will be created
   * @param rcuPrefix  RCU schema prefix
   * @param password RCU schema passoword that is being changed to
   * @param rcuDbConnString RCU database connection string
   */
  public static void updateRcuAccessSecret(String secretName, String namespace,
                                           String rcuPrefix, String password, String rcuDbConnString) {

    assertTrue(Kubernetes.deleteSecret(secretName, namespace),
        String.format("create secret failed for %s", secretName));
    createRcuAccessSecret(secretName, namespace, rcuPrefix, password, rcuDbConnString);

  }

}
