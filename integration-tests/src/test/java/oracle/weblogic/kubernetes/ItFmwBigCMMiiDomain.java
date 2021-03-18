// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1SecretReference;
import io.kubernetes.client.openapi.models.V1Service;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.Opss;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.DbUtils;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.kubernetes.client.util.Yaml.dump;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.DB_IMAGE_TO_USE_IN_SPEC;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.FMWINFRA_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.FMWINFRA_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.FMWINFRA_IMAGE_TO_USE_IN_SPEC;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OCIR_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.RESULTS_ROOT;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.listServices;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createOcirRepoSecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createOpsswalletpasswordSecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createRcuAccessSecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getExternalServicePodName;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.DbUtils.setupDBandRCUschema;
import static oracle.weblogic.kubernetes.utils.FileUtils.replaceStringInFile;
import static oracle.weblogic.kubernetes.utils.TestUtils.callWebAppAndWaitTillReady;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to creat a FMW domain in model in image with over 1Mb data
 * to test that generated introspector Config Maps will be splitted to smaller than 1Mg
 * and domain will be started and running.
 */
@DisplayName("Test to a create FMW model in image domain "
    + "with introspect Config Map bigger then 1 Mb and start the domain")
@IntegrationTest
public class ItFmwBigCMMiiDomain {

  private static String dbNamespace = null;
  private static String opNamespace = null;
  private static String jrfDomainNamespace = null;
  private static String jrfMiiImage = null;

  private static final String RCUSCHEMAPREFIX = "jrfdomainmii";
  private static final String ORACLEDBURLPREFIX = "oracledb.";
  private static final String ORACLEDBSUFFIX = ".svc.cluster.local:1521/devpdb.k8s";
  private static final String RCUSYSUSERNAME = "sys";
  private static final String RCUSYSPASSWORD = "Oradoc_db1";
  private static final String RCUSCHEMAUSERNAME = "myrcuuser";
  private static final String RCUSCHEMAPASSWORD = "Oradoc_db1";
  private static final String modelFile = "model-bigcm-jrf.yaml";
  private static final String PROPS_TEMP_DIR = RESULTS_ROOT + "/fwmdomaintemp";
  private static String dbUrl = null;
  private static LoggingFacade logger = null;

  private String domainUid = "jrfdomain-mii";
  private String adminServerPodName = domainUid + "-admin-server";
  private String managedServerPrefix = domainUid + "-managed-server";
  private int replicaCount = 2;
  private String adminSecretName = domainUid + "-weblogic-credentials";
  private String encryptionSecretName = domainUid + "-encryptionsecret";
  private String rcuaccessSecretName = domainUid + "-rcu-access";
  private String opsswalletpassSecretName = domainUid + "-opss-wallet-password-secret";
  private String opsswalletfileSecretName = domainUid + "opss-wallet-file-secret";
  static int dbNodePort;

  // create standard, reusable retry/backoff policy
  private static final ConditionFactory withStandardRetryPolicy
      = with().pollDelay(2, SECONDS)
      .and().with().pollInterval(10, SECONDS)
      .atMost(5, MINUTES).await();


  /**
   * Start DB service and create RCU schema.
   * Assigns unique namespaces for operator and domain.
   * Pull FMW image and Oracle DB image.
   * Installs operator.
   *
   * @param namespaces injected by JUnit
   */
  @BeforeAll
  public static void initAll(@Namespaces(3) List<String> namespaces) {

    logger = getLogger();
    logger.info("Assign a unique namespace for DB and RCU");
    assertNotNull(namespaces.get(0), "Namespace is null");
    dbNamespace = namespaces.get(0);
    dbUrl = ORACLEDBURLPREFIX + dbNamespace + ORACLEDBSUFFIX;

    logger.info("Assign a unique namespace for operator");
    assertNotNull(namespaces.get(1), "Namespace is null");
    opNamespace = namespaces.get(1);

    logger.info("Assign a unique namespace for FMW domain");
    assertNotNull(namespaces.get(2), "Namespace is null");
    jrfDomainNamespace = namespaces.get(2);

    logger.info("Start DB and create RCU schema for namespace: {0}, RCU prefix: {1}, "
            + "dbUrl: {2}, dbImage: {3},  fmwImage: {4} ", dbNamespace, RCUSCHEMAPREFIX, dbUrl,
        DB_IMAGE_TO_USE_IN_SPEC, FMWINFRA_IMAGE_TO_USE_IN_SPEC);
    assertDoesNotThrow(() -> setupDBandRCUschema(DB_IMAGE_TO_USE_IN_SPEC, FMWINFRA_IMAGE_TO_USE_IN_SPEC,
        RCUSCHEMAPREFIX, dbNamespace, 0, dbUrl),
        String.format("Failed to create RCU schema for prefix %s in the namespace %s with "
            + "dbUrl %s", RCUSCHEMAPREFIX, dbNamespace, dbUrl));

    dbNodePort = DbUtils.getDBNodePort(dbNamespace, "oracledb");
    logger.info("DB Node Port = {0}", dbNodePort);
    // install operator and verify its running in ready state
    installAndVerifyOperator(opNamespace, jrfDomainNamespace);

    logger.info("For ItFmwMiiDomain using DB image: {0}, FMW image {1}",
        DB_IMAGE_TO_USE_IN_SPEC, FMWINFRA_IMAGE_TO_USE_IN_SPEC);

  }

  /**
   * Create a FMW model in image domain with big data.
   * Verify Pod is ready and service exists for both admin server and managed servers.
   * Verify EM console is accessible.
   * Verify that multiple introspector CMs are produced if data is > 1Mb
   */
  @Test
  @DisplayName("Create FMW Domain model in image with big introspector CM")
  public void testFmwBigCMModelInImage() {

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createOcirRepoSecret(jrfDomainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        adminSecretName,
        jrfDomainNamespace,
        "weblogic",
        "welcome1"),
        String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        encryptionSecretName,
        jrfDomainNamespace,
        "weblogicenc",
        "weblogicenc"),
        String.format("createSecret failed for %s", encryptionSecretName));

    // create RCU access secret
    logger.info("Creating RCU access secret: {0}, with prefix: {1}, dbUrl: {2}, schemapassword: {3})",
        rcuaccessSecretName, RCUSCHEMAPREFIX, RCUSCHEMAPASSWORD, dbUrl);
    assertDoesNotThrow(() -> createRcuAccessSecret(
        rcuaccessSecretName,
        jrfDomainNamespace,
        RCUSCHEMAPREFIX,
        RCUSCHEMAPASSWORD,
        dbUrl),
        String.format("createSecret failed for %s", rcuaccessSecretName));

    logger.info("Create OPSS wallet password secret");
    assertDoesNotThrow(() -> createOpsswalletpasswordSecret(
        opsswalletpassSecretName,
        jrfDomainNamespace,
        "welcome1"),
        String.format("createSecret failed for %s", opsswalletpassSecretName));

    logger.info("Create an image with jrf model file");

    String text = "SOMEVERYVERYVERYBIGDATAFORPROPERTY";
    int numberOfLines = 100000;
    StringBuffer propVal = new StringBuffer();
    for (int i = 0; i < numberOfLines; i++) {
      propVal.append(text);
    }
    // create a temporary model file with 1M data stored
    File testmodelFile = assertDoesNotThrow(() ->
            File.createTempFile("modelBigCM", ".yaml"),
        "Failed to create domain properties file");

    final Path srcModelFile = Paths.get(MODEL_DIR, modelFile);
    final Path targetModelFile = Paths.get(testmodelFile.toString());
    assertDoesNotThrow(() ->  Files.copy(srcModelFile, targetModelFile, StandardCopyOption.REPLACE_EXISTING),
        "Failed to copy file " + srcModelFile + " to file " + targetModelFile);

    assertDoesNotThrow(() -> replaceStringInFile(targetModelFile.toString(),
        "BIGDATAREPLACE",
        propVal.toString()), "Can't replace the string BIGDATAREPLACE in " + targetModelFile);
    assertDoesNotThrow(() -> replaceStringInFile(targetModelFile.toString(),
        "@@PROP:K8S_NODEPORT_HOST@@:@@PROP:DBPORT@@",
        String.format("%s:%s", K8S_NODEPORT_HOST, Integer.toString(dbNodePort))),
        "Can't replace the string @@PROP:K8S_NODEPORT_HOST@@:@@PROP:DBPORT@@ in " + targetModelFile);

    final List<String> modelList = Collections.singletonList(targetModelFile.toString());
    String jrfMii1Image = createMiiImageAndVerify(
        "jrf-mii-image",
        modelList,
        Collections.singletonList(MII_BASIC_APP_NAME),
        FMWINFRA_IMAGE_NAME,
        FMWINFRA_IMAGE_TAG,
        "JRF",
        false);

    // push the image to a registry to make it accessible in multi-node cluster
    dockerLoginAndPushImageToRegistry(jrfMii1Image);

    // create the domain object
    Domain domain = createDomainResource(domainUid,
        jrfDomainNamespace,
        adminSecretName,
        OCIR_SECRET_NAME,
        encryptionSecretName,
        rcuaccessSecretName,
        opsswalletpassSecretName,
        replicaCount,
        jrfMii1Image);

    createDomainAndVerify(domain, jrfDomainNamespace);
    verifyDomainReady(jrfDomainNamespace, domainUid);
    // check if multiple configmaps are created
    try {
      if (!Kubernetes.listConfigMaps(jrfDomainNamespace).getItems().isEmpty()) {
        logger.info("Getting Config Maps List");
        int cmTotalSize = 0;
        List<V1ConfigMap> items = Kubernetes.listConfigMaps(jrfDomainNamespace).getItems();
        List<V1ConfigMap> itemsCM = new ArrayList<>();
        for (var item : items) {
          if (item.getMetadata().getName().contains("introspect")) {
            logger.info("Found ConfigMap " + item.getMetadata().getName());
            logger.info("Found ConfigMap size " + item.toString().getBytes("UTF-8").length + " bytes");
            cmTotalSize = cmTotalSize + item.toString().getBytes("UTF-8").length;
            itemsCM.add(item);
          }
        }
        assertTrue((cmTotalSize > 1000000) && (itemsCM.size() > 1),
            "Produced introspector domain cm is bigger than 1M and was not splitted");
      }
    } catch (Exception ex) {
      throw new RuntimeException("Failed to process config maps" + ex.getMessage());
    }
  }

  /**
   * Construct a domain object with the given parameters that can be used to create a domain resource.
   * @param domainUid unique Uid of the domain
   * @param domNamespace  namespace where the domain exists
   * @param adminSecretName  name of admin secret
   * @param repoSecretName name of repository secret
   * @param encryptionSecretName name of encryption secret
   * @param rcuAccessSecretName name of RCU access secret
   * @param opssWalletPasswordSecretName name of opss wallet password secret
   * @param replicaCount count of replicas
   * @param miiImage name of model in image
   * @return Domain WebLogic domain
   */
  private Domain createDomainResource(
      String domainUid, String domNamespace, String adminSecretName,
      String repoSecretName, String encryptionSecretName, String rcuAccessSecretName,
      String opssWalletPasswordSecretName, int replicaCount, String miiImage) {
    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .imagePullPolicy("IfNotPresent")
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(0))))
            .addClustersItem(new Cluster()
                .clusterName("cluster-1")
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                .opss(new Opss()
                    .walletPasswordSecret(opssWalletPasswordSecretName))
                .model(new Model()
                    .domainType("JRF")
                    .runtimeEncryptionSecret(encryptionSecretName))
                .addSecretsItem(rcuAccessSecretName)
                .introspectorJobActiveDeadlineSeconds(600L)));

    return domain;
  }

  /**
   * Verify Pod is ready and service exists for both admin server and managed servers.
   * Verify EM console is accessible.
   */
  private void verifyDomainReady(String domainNamespace, String domainUid) {
    String adminServerPodName = domainUid + "-admin-server";
    String managedServerPrefix = domainUid + "-managed-server";
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, domainNamespace);

    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Checking managed server service {0} is created in namespace {1}",
          managedServerPrefix + i + "-c1", domainNamespace);
      checkPodReadyAndServiceExists(managedServerPrefix + i + "-c1", domainUid, domainNamespace);
    }

    //check access to the em console: http://hostname:port/em
    int nodePort = getServiceNodePort(
        domainNamespace, getExternalServicePodName(adminServerPodName), "default");
    assertTrue(nodePort != -1,
        "Could not get the default external service node port");
    logger.info("Found the default service nodePort {0}", nodePort);
    String curlCmd1 = "curl -s -L --show-error --noproxy '*' "
        + " http://" + K8S_NODEPORT_HOST + ":" + nodePort
        + "/em --write-out %{http_code} -o /dev/null";
    logger.info("Executing default nodeport curl command {0}", curlCmd1);
    assertTrue(callWebAppAndWaitTillReady(curlCmd1, 5), "Calling web app failed");
    logger.info("EM console is accessible thru default service");
  }
}