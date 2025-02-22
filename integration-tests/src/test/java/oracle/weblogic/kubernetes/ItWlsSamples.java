// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.BASE_IMAGES_REPO_SECRET;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_IMAGES_REPO;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.PV_ROOT;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TO_USE_IN_SPEC;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ITTESTS_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainDoesNotExist;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.pvExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.pvcExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.secretExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkClusterReplicaCountMatches;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.FileUtils.replaceStringInFile;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createOcirRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createSecretForBaseImages;
import static oracle.weblogic.kubernetes.utils.ImageUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodDoesNotExist;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodExists;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.apache.commons.io.FileUtils.copyDirectory;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests related to samples.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Verify the domain on pv, domain in image samples using wlst and wdt and domain lifecycle scripts")
@IntegrationTest
class ItWlsSamples {

  public static final String SERVER_LIFECYCLE = "Server";
  public static final String CLUSTER_LIFECYCLE = "Cluster";
  public static final String DOMAIN = "DOMAIN";
  public static final String STOP_SERVER_SCRIPT = "stopServer.sh";
  public static final String START_SERVER_SCRIPT = "startServer.sh";
  public static final String STOP_CLUSTER_SCRIPT = "stopCluster.sh";
  public static final String START_CLUSTER_SCRIPT = "startCluster.sh";
  public static final String STOP_DOMAIN_SCRIPT = "stopDomain.sh";
  public static final String START_DOMAIN_SCRIPT = "startDomain.sh";

  private static String traefikNamespace = null;
  private static String nginxNamespace = null;
  private static String voyagerNamespace = null;
  private static String domainNamespace = null;
  private static final String domainName = "domain1";
  private static final String diiImageNameBase = "domain-home-in-image";
  private static final String diiImageTag = "12.2.1.4";
  private final int replicaCount = 2;
  private final String clusterName = "cluster-1";
  private final String managedServerNameBase = "managed-server";
  private final String managedServerPodNamePrefix = domainName + "-" + managedServerNameBase;

  private final Path samplePath = Paths.get(ITTESTS_DIR, "../kubernetes/samples");
  private final Path tempSamplePath = Paths.get(WORK_DIR, "wls-sample-testing");
  private final Path domainLifecycleSamplePath = Paths.get(samplePath + "/scripts/domain-lifecycle");
  private static String UPDATE_MODEL_FILE = "model-samples-update-domain.yaml";
  private static String UPDATE_MODEL_PROPERTIES = "model-samples-update-domain.properties";

  private static final String[] params = {"wlst:domain1", "wdt:domain2"};

  private static LoggingFacade logger = null;

  /**
   * Assigns unique namespaces for operator and domains and installs operator.
   *
   * @param namespaces injected by JUnit
   */
  @BeforeAll
  public static void initAll(@Namespaces(5) List<String> namespaces) {
    logger = getLogger();

    logger.info("Assign a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace is null");
    String opNamespace = namespaces.get(0);
    logger.info("Assign a unique namespace for WebLogic domain");
    assertNotNull(namespaces.get(1), "Namespace is null");
    domainNamespace = namespaces.get(1);

    logger.info("Assign a unique namespace for Traefik controller");
    assertNotNull(namespaces.get(2), "Namespace is null");
    traefikNamespace = namespaces.get(2);

    logger.info("Assign a unique namespace for Nginx controller");
    assertNotNull(namespaces.get(3), "Namespace is null");
    nginxNamespace = namespaces.get(3);

    logger.info("Assign a unique namespace for Voyager controller");
    assertNotNull(namespaces.get(4), "Namespace is null");
    voyagerNamespace = namespaces.get(4);

    // create pull secrets for WebLogic image when running in non Kind Kubernetes cluster
    // this secret is used only for non-kind cluster
    createSecretForBaseImages(domainNamespace);

    // install operator and verify its running in ready state
    installAndVerifyOperator(opNamespace, domainNamespace);
  }

  /**
   * Test domain in image samples using domains created by image tool using wlst and wdt.
   *
   */
  @Order(1)
  @ParameterizedTest
  @MethodSource("paramProvider")
  @DisplayName("Test samples using domain in image")
  void testSampleDomainInImage(String model) {
    String domainName = model.split(":")[1];
    String script = model.split(":")[0];
    String imageName = DOMAIN_IMAGES_REPO + diiImageNameBase + "_" + script + ":" + diiImageTag;

    //copy the samples directory to a temporary location
    setupSample();
    createSecretWithUsernamePassword(domainName + "-weblogic-credentials", domainNamespace,
            ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    Path sampleBase = Paths.get(tempSamplePath.toString(), "scripts/create-weblogic-domain/domain-home-in-image");

    // update create-domain-inputs.yaml with the values from this test
    updateDomainInputsFile(domainName, sampleBase);

    // update domainHomeImageBase with right values in create-domain-inputs.yaml
    assertDoesNotThrow(() -> {
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "domainHomeImageBase: container-registry.oracle.com/middleware/weblogic:" + diiImageTag,
              "domainHomeImageBase: " + WEBLOGIC_IMAGE_TO_USE_IN_SPEC);
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "#image:",
              "image: " + imageName);
    });

    if (script.equals("wlst")) {
      assertDoesNotThrow(() -> {
        replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
            "mode: wdt",
            "mode: wlst");
      });
    }

    // build the command to run create-domain.sh
    String additonalOptions = " -u "
            + ADMIN_USERNAME_DEFAULT
            + " -p "
            + ADMIN_PASSWORD_DEFAULT;


    String[] additonalStr = {additonalOptions, imageName};

    // run create-domain.sh to create domain.yaml file, run kubectl to create the domain and verify
    createDomainAndVerify(domainName, sampleBase, additonalStr);

    //delete the domain resource
    deleteDomainResourceAndVerify(domainName, sampleBase);
  }

  /**
   * Test domain in pv samples using domains created by wlst and wdt.
   * In domain on pv using wdt and wlst usecases, we also run the update domain script from the samples,
   * to add a cluster to the domain.
   *
   * @param model domain name and script type to create domain. Acceptable values of format String:wlst|wdt
   */
  @Order(2)
  @ParameterizedTest
  @MethodSource("paramProvider")
  @DisplayName("Test samples using domain in pv")
  void testSampleDomainInPv(String model) {

    String domainName = model.split(":")[1];
    String script = model.split(":")[0];

    //copy the samples directory to a temporary location
    setupSample();
    String secretName = domainName + "-weblogic-credentials";
    if (!secretExists(secretName, domainNamespace)) {
      createSecretWithUsernamePassword(
          secretName,
          domainNamespace,
          ADMIN_USERNAME_DEFAULT,
          ADMIN_PASSWORD_DEFAULT);
    }
    //create PV and PVC used by the domain
    createPvPvc(domainName);

    // WebLogic secrets for the domain has been created by previous test
    // No need to create it again

    Path sampleBase = Paths.get(tempSamplePath.toString(), "scripts/create-weblogic-domain/domain-home-on-pv");

    // update create-domain-inputs.yaml with the values from this test
    updateDomainInputsFile(domainName, sampleBase);

    // change namespace from default to custom, set wlst or wdt, domain name, and t3PublicAddress
    assertDoesNotThrow(() -> {
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "createDomainFilesDir: wlst", "createDomainFilesDir: " + script);
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "image: container-registry.oracle.com/middleware/weblogic:12.2.1.4",
              "image: " + WEBLOGIC_IMAGE_TO_USE_IN_SPEC);
    });

    // run create-domain.sh to create domain.yaml file, run kubectl to create the domain and verify
    createDomainAndVerify(domainName, sampleBase);

    // update the domain to add a new cluster
    copyModelFileForUpdateDomain(sampleBase);
    updateDomainAndVerify(domainName, sampleBase, domainNamespace, script);
  }

  /**
   * Test scripts for stopping and starting a managed server.
   */
  @Order(3)
  @Test
  @DisplayName("Test server lifecycle samples scripts")
  void testServerLifecycleScripts() {

    // Verify that stopServer script execution shuts down server pod and replica count is decremented
    String serverName = managedServerNameBase + "1";
    executeLifecycleScript(STOP_SERVER_SCRIPT, SERVER_LIFECYCLE, serverName);
    checkPodDoesNotExist(managedServerPodNamePrefix + "1", domainName, domainNamespace);
    assertDoesNotThrow(() -> {
      checkClusterReplicaCountMatches(clusterName, domainName, domainNamespace, 1);
    });

    // Verify that startServer script execution starts server pod and replica count is incremented
    executeLifecycleScript(START_SERVER_SCRIPT, SERVER_LIFECYCLE, serverName);
    checkPodExists(managedServerPodNamePrefix + "1", domainName, domainNamespace);
    assertDoesNotThrow(() -> {
      checkClusterReplicaCountMatches(clusterName, domainName, domainNamespace, 2);
    });
  }

  /**
   * Test scripts for stopping and starting a managed server while keeping replica count constant.
   */
  @Order(4)
  @Test
  @DisplayName("Test server lifecycle samples scripts with constant replica count")
  void testServerLifecycleScriptsWithConstantReplicaCount() {
    String serverName = managedServerNameBase + "1";
    String keepReplicaCountConstantParameter = "-k";
    // Verify that replica count is not changed when using "-k" parameter and a replacement server is started
    executeLifecycleScript(STOP_SERVER_SCRIPT, SERVER_LIFECYCLE, serverName, keepReplicaCountConstantParameter);
    checkPodDoesNotExist(managedServerPodNamePrefix + "1", domainName, domainNamespace);
    checkPodExists(managedServerPodNamePrefix + "3", domainName, domainNamespace);
    assertDoesNotThrow(() -> {
      checkClusterReplicaCountMatches(clusterName, domainName, domainNamespace, 2);
    });

    // Verify that replica count is not changed when using "-k" parameter and replacement server is shutdown
    executeLifecycleScript(START_SERVER_SCRIPT, SERVER_LIFECYCLE, serverName, keepReplicaCountConstantParameter);
    checkPodExists(managedServerPodNamePrefix + "1", domainName, domainNamespace);
    checkPodDoesNotExist(managedServerPodNamePrefix + "3", domainName, domainNamespace);
    assertDoesNotThrow(() -> {
      checkClusterReplicaCountMatches(clusterName, domainName, domainNamespace, 2);
    });
  }

  /**
   * Test scripts for stopping and starting a cluster.
   */
  @Order(5)
  @Test
  @DisplayName("Test cluster lifecycle scripts")
  void testClusterLifecycleScripts() {

    // Verify all clustered server pods are shut down after stopCluster script execution
    executeLifecycleScript(STOP_CLUSTER_SCRIPT, CLUSTER_LIFECYCLE, clusterName);
    for (int i = 1; i <= replicaCount; i++) {
      checkPodDoesNotExist(managedServerPodNamePrefix + i, domainName, domainNamespace);
    }

    // Verify all clustered server pods are started after startCluster script execution
    executeLifecycleScript(START_CLUSTER_SCRIPT, CLUSTER_LIFECYCLE, clusterName);
    for (int i = 1; i <= replicaCount; i++) {
      checkPodExists(managedServerPodNamePrefix + i, domainName, domainNamespace);
    }
  }

  /**
   * Test scripts for stopping and starting a domain.
   */
  @Order(6)
  @Test
  @DisplayName("Test domain lifecycle scripts")
  void testDomainLifecycleScripts() {
    // Verify all WebLogic server instance pods are shut down after stopDomain script execution
    executeLifecycleScript(STOP_DOMAIN_SCRIPT, DOMAIN, null);
    for (int i = 1; i <= replicaCount; i++) {
      checkPodDoesNotExist(managedServerPodNamePrefix + i, domainName, domainNamespace);
    }
    String adminServerName = "admin-server";
    String adminServerPodName = domainName + "-" + adminServerName;
    checkPodDoesNotExist(adminServerPodName, domainName, domainNamespace);

    // Verify all WebLogic server instance pods are started after startDomain script execution
    executeLifecycleScript(START_DOMAIN_SCRIPT, DOMAIN, null);
    for (int i = 1; i <= replicaCount; i++) {
      checkPodExists(managedServerPodNamePrefix + i, domainName, domainNamespace);
    }
    checkPodExists(adminServerPodName, domainName, domainNamespace);
  }

  /**
   * Verify setupLoadBalancer scripts for managing Traefik LoadBalancer.
   */
  @Order(7)
  @Test
  @DisplayName("Manage Traefik Ingress Controller with setupLoadBalancer")
  void testTraefikIngressController() {
    setupSample();
    Path scriptBase = Paths.get(tempSamplePath.toString(), "charts/util");
    setupLoadBalancer(scriptBase, "traefik", " -c -n " + traefikNamespace);
    setupLoadBalancer(scriptBase, "traefik", " -d -n " + traefikNamespace);
  }

  /**
   * Verify setupLoadBalancer scripts for managing Voyager LoadBalancer.
   */
  @Order(8)
  @Test
  @DisplayName("Manage Voyager Ingress Controller with setupLoadBalancer")
  void testVoyagerIngressController() {
    setupSample();
    Path scriptBase = Paths.get(tempSamplePath.toString(), "charts/util");
    setupLoadBalancer(scriptBase, "voyager", " -c -n " + voyagerNamespace);
    setupLoadBalancer(scriptBase, "voyager", " -d -n " + voyagerNamespace);
  }

  /**
   * Verify setupLoadBalancer scripts for managing Nginx LoadBalancer.
   */
  @Order(9)
  @Test
  @DisplayName("Manage Nginx Ingress Controller with setupLoadBalancer")
  void testNginxIngressController() {
    setupSample();
    Path scriptBase = Paths.get(tempSamplePath.toString(), "charts/util");
    setupLoadBalancer(scriptBase, "nginx", " -c -n " + nginxNamespace);
    setupLoadBalancer(scriptBase, "nginx", " -d -n " + nginxNamespace);
  }

  // Function to execute domain lifecyle scripts
  private void executeLifecycleScript(String script, String scriptType, String entityName) {
    executeLifecycleScript(script, scriptType, entityName, "");
  }

  // Function to execute domain lifecyle scripts
  private void executeLifecycleScript(String script, String scriptType, String entityName, String extraParams) {
    CommandParams params;
    boolean result;
    String commonParameters;
    // This method assumes that the domain lifecycle is run on on domain1 only. The update-domain.sh is only
    // for domain-on-pv with wdt use case. So, setting the domain name and namespace using the extraParams args
    if (scriptType.equals("INTROSPECT_DOMAIN")) {
      commonParameters = extraParams;
    } else {
      commonParameters = " -d " + domainName + " -n " + domainNamespace;
    }
    params = new CommandParams().defaults();
    if (scriptType.equals(SERVER_LIFECYCLE)) {
      params.command("sh "
              + Paths.get(domainLifecycleSamplePath.toString(), "/" + script).toString()
              + commonParameters + " -s " + entityName + " " + extraParams);
    } else if (scriptType.equals(CLUSTER_LIFECYCLE)) {
      params.command("sh "
              + Paths.get(domainLifecycleSamplePath.toString(), "/" + script).toString()
              + commonParameters + " -c " + entityName);
    } else {
      params.command("sh "
              + Paths.get(domainLifecycleSamplePath.toString(), "/" + script).toString()
              + commonParameters);
    }
    result = Command.withParams(params).execute();
    assertTrue(result, "Failed to execute script " + script);
  }

  // generates the stream of objects used by parametrized test.
  private static Stream<String> paramProvider() {
    return Arrays.stream(params);
  }

  // copy samples directory to a temporary location
  private void setupSample() {
    assertDoesNotThrow(() -> {
      // copy ITTESTS_DIR + "../kubernates/samples" to WORK_DIR + "/wls-sample-testing"
      logger.info("Deleting and recreating {0}", tempSamplePath);
      Files.createDirectories(tempSamplePath);
      deleteDirectory(tempSamplePath.toFile());
      Files.createDirectories(tempSamplePath);

      logger.info("Copying {0} to {1}", samplePath, tempSamplePath);
      copyDirectory(samplePath.toFile(), tempSamplePath.toFile());
    });
  }

  private void copyModelFileForUpdateDomain(Path sampleBase) {
    assertDoesNotThrow(() -> {
      copyFile(Paths.get(MODEL_DIR, UPDATE_MODEL_FILE).toFile(),
               Paths.get(sampleBase.toString(), UPDATE_MODEL_FILE).toFile());
      // create a properties file that is needed with this model file
      List<String> lines = Arrays.asList("clusterName2=cluster-2", "managedServerNameBaseC2=c2-managed-server");
      Files.write(Paths.get(sampleBase.toString(), UPDATE_MODEL_PROPERTIES), lines, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    });
  }

  // create persistent volume and persistent volume claims used by the samples
  private void createPvPvc(String domainName) {

    String pvName = domainName + "-weblogic-sample-pv";
    String pvcName = domainName + "-weblogic-sample-pvc";

    Path pvpvcBase = Paths.get(tempSamplePath.toString(),
        "scripts/create-weblogic-domain-pv-pvc");

    // create pv and pvc
    assertDoesNotThrow(() -> {
      // when tests are running in local box the PV directories need to exist
      Path pvHostPath;
      pvHostPath = Files.createDirectories(Paths.get(PV_ROOT, this.getClass().getSimpleName(), pvName));

      logger.info("Creating PV directory host path {0}", pvHostPath);
      deleteDirectory(pvHostPath.toFile());
      Files.createDirectories(pvHostPath);

      // set the pvHostPath in create-pv-pvc-inputs.yaml
      replaceStringInFile(Paths.get(pvpvcBase.toString(), "create-pv-pvc-inputs.yaml").toString(),
          "#weblogicDomainStoragePath: /scratch/k8s_dir", "weblogicDomainStoragePath: " + pvHostPath);
      // set the namespace in create-pv-pvc-inputs.yaml
      replaceStringInFile(Paths.get(pvpvcBase.toString(), "create-pv-pvc-inputs.yaml").toString(),
          "namespace: default", "namespace: " + domainNamespace);
      // set the pv storage policy to Recycle in create-pv-pvc-inputs.yaml
      replaceStringInFile(Paths.get(pvpvcBase.toString(), "create-pv-pvc-inputs.yaml").toString(),
          "weblogicDomainStorageReclaimPolicy: Retain", "weblogicDomainStorageReclaimPolicy: Recycle");
      replaceStringInFile(Paths.get(pvpvcBase.toString(), "create-pv-pvc-inputs.yaml").toString(),
          "domainUID:", "domainUID: " + domainName);
    });

    // generate the create-pv-pvc-inputs.yaml
    CommandParams params = new CommandParams().defaults();
    params.command("sh "
        + Paths.get(pvpvcBase.toString(), "create-pv-pvc.sh").toString()
        + " -i " + Paths.get(pvpvcBase.toString(), "create-pv-pvc-inputs.yaml").toString()
        + " -o "
        + Paths.get(pvpvcBase.toString()));

    boolean result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create create-pv-pvc-inputs.yaml");

    //create pv and pvc
    params = new CommandParams().defaults();
    params.command("kubectl create -f " + Paths.get(pvpvcBase.toString(),
        "pv-pvcs/" + domainName + "-weblogic-sample-pv.yaml").toString());
    result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create pv");

    testUntil(
        assertDoesNotThrow(() -> pvExists(pvName, null),
          String.format("pvExists failed with ApiException for pv %s", pvName)),
        logger,
        "pv {0} to be ready",
        pvName);

    params = new CommandParams().defaults();
    params.command("kubectl create -f " + Paths.get(pvpvcBase.toString(),
        "pv-pvcs/" + domainName + "-weblogic-sample-pvc.yaml").toString());
    result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create pvc");

    testUntil(
        assertDoesNotThrow(() -> pvcExists(pvcName, domainNamespace),
          String.format("pvcExists failed with ApiException for pvc %s", pvcName)),
        logger,
        "pv {0} to be ready in namespace {1}",
        pvcName,
        domainNamespace);
  }

  private void updateDomainInputsFile(String domainName, Path sampleBase) {
    // change namespace from default to custom, domain name, and t3PublicAddress
    assertDoesNotThrow(() -> {
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "namespace: default", "namespace: " + domainNamespace);
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "domain1", domainName);
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "#t3PublicAddress:", "t3PublicAddress: " + K8S_NODEPORT_HOST);
      replaceStringInFile(Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString(),
              "#imagePullSecretName:", "imagePullSecretName: " + BASE_IMAGES_REPO_SECRET);
    });
  }

  private void createDomainAndVerify(String domainName, Path sampleBase, String... additonalStr) {
    String additionalOptions = (additonalStr.length == 0) ? "" : additonalStr[0];
    String imageName = (additonalStr.length == 2) ? additonalStr[1] : "";

    // run create-domain.sh to create domain.yaml file
    CommandParams params = new CommandParams().defaults();
    params.command("sh "
            + Paths.get(sampleBase.toString(), "create-domain.sh").toString()
            + " -i " + Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString()
            + " -o "
            + Paths.get(sampleBase.toString())
            + additionalOptions);

    logger.info("Run create-domain.sh to create domain.yaml file");
    boolean result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create domain.yaml");

    if (sampleBase.toString().contains("domain-home-in-image")) {
      // docker login and push image to docker registry if necessary
      logger.info("Push the image {0} to Docker repo", imageName);
      dockerLoginAndPushImageToRegistry(imageName);

      // create docker registry secret to pull the image from registry
      // this secret is used only for non-kind cluster
      logger.info("Create docker registry secret in namespace {0}", domainNamespace);
      createOcirRepoSecret(domainNamespace);
    }

    // run kubectl to create the domain
    logger.info("Run kubectl to create the domain");
    params = new CommandParams().defaults();
    params.command("kubectl apply -f "
            + Paths.get(sampleBase.toString(), "weblogic-domains/" + domainName + "/domain.yaml").toString());

    result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create domain custom resource");

    // wait for the domain to exist
    logger.info("Checking for domain custom resource in namespace {0}", domainNamespace);
    testUntil(
        domainExists(domainName, DOMAIN_VERSION, domainNamespace),
        logger,
        "domain {0} to be created in namespace {1}",
        domainName,
        domainNamespace);

    final String adminServerName = "admin-server";
    final String adminServerPodName = domainName + "-" + adminServerName;

    final String managedServerNameBase = "managed-server";
    String managedServerPodNamePrefix = domainName + "-" + managedServerNameBase;
    int replicaCount = 2;

    // verify the admin server service and pod is created
    checkPodReadyAndServiceExists(adminServerPodName, domainName, domainNamespace);

    // verify managed server services created and pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      checkPodReadyAndServiceExists(managedServerPodNamePrefix + i, domainName, domainNamespace);
    }
  }

  private void updateDomainAndVerify(String domainName,
                                     Path sampleBase,
                                     String domainNamespace,
                                     String script) {
    //First copy the update model file to wdt dir and rename it wdt-model_dynamic.yaml
    assertDoesNotThrow(() -> {
      copyFile(Paths.get(sampleBase.toString(), UPDATE_MODEL_FILE).toFile(),
          Paths.get(sampleBase.toString(), "wdt/wdt_model_dynamic.yaml").toFile());
    });
    // run update-domain.sh to create domain.yaml file
    CommandParams params = new CommandParams().defaults();
    params.command("sh "
        + Paths.get(sampleBase.toString(), "update-domain.sh").toString()
        + " -i " + Paths.get(sampleBase.toString(), "create-domain-inputs.yaml").toString()
        + "," + Paths.get(sampleBase.toString(), UPDATE_MODEL_PROPERTIES).toString()
        + " -o "
        + Paths.get(sampleBase.toString()));

    logger.info("Run update-domain.sh to create domain.yaml file");
    boolean result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create domain.yaml");

    // For the domain created by WLST, we have to apply domain.yaml created by update-domain.sh
    // before initiating introspection of the domain to start the second cluster that was just added
    // otherwise the newly added Cluster 'cluster-2' is not added to the domain1.
    if (script.equals("wlst")) {
      // run kubectl to update the domain
      logger.info("Run kubectl to create the domain");
      params = new CommandParams().defaults();
      params.command("kubectl apply -f "
          + Paths.get(sampleBase.toString(), "weblogic-domains/"
          + domainName
          + "/domain.yaml").toString());

      result = Command.withParams(params).execute();
      assertTrue(result, "Failed to create domain custom resource");
    }

    // Have to initiate introspection of the domain to start the second cluster that was just added
    // Call introspectDomain.sh
    String extraParams = " -d " + domainName + " -n " + domainNamespace;
    executeLifecycleScript("introspectDomain.sh", "INTROSPECT_DOMAIN", "", extraParams);

    final String adminServerName = "admin-server";
    final String adminServerPodName = domainName + "-" + adminServerName;

    final String managedServerNameBase = "c2-managed-server";
    String managedServerPodNamePrefix = domainName + "-" + managedServerNameBase;
    int replicaCount = 2;

    // verify the admin server service and pod is created
    checkPodReadyAndServiceExists(adminServerPodName, domainName, domainNamespace);

    // verify managed server services created and pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      checkPodReadyAndServiceExists(managedServerPodNamePrefix + i, domainName, domainNamespace);
    }
  }

  private void deleteDomainResourceAndVerify(String domainName, Path sampleBase) {
    //delete the domain resource
    CommandParams params = new CommandParams().defaults();
    params.command("kubectl delete -f "
            + Paths.get(sampleBase.toString(), "weblogic-domains/"
            + domainName + "/domain.yaml").toString());
    boolean result = Command.withParams(params).execute();
    assertTrue(result, "Failed to delete domain custom resource");

    testUntil(
        domainDoesNotExist(domainName, DOMAIN_VERSION, domainNamespace),
        logger,
        "domain {0} to be deleted in namespace {1}",
        domainName,
        domainNamespace);
  }

  private void setupLoadBalancer(Path sampleBase, String ingressType, String additionalOptions) {
    // run setupLoadBalancer.sh to install/uninstall ingress controller
    CommandParams params = new CommandParams().defaults();
    params.command("sh "
           + Paths.get(sampleBase.toString(), "setupLoadBalancer.sh").toString()
           + " -t " + ingressType
           + additionalOptions);
    logger.info("Run setupLoadBalancer.sh to manage {0} ingress controller", ingressType);
    boolean result = Command.withParams(params).execute();
    assertTrue(result, "Failed to manage ingress controller");
  }

}
