// Copyright (c) 2017, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1beta1PodDisruptionBudget;
import io.kubernetes.client.util.Yaml;
import org.apache.commons.codec.digest.DigestUtils;

/** Annotates pods, services with details about the Domain instance and checks these annotations. */
public class AnnotationHelper {
  static final String SHA256_ANNOTATION = "weblogic.sha256";
  private static final boolean DEBUG = false;
  private static final String HASHED_STRING = "hashedString";
  @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
  private static Function<Object, String> HASH_FUNCTION = o -> DigestUtils.sha256Hex(Yaml.dump(o));

  /**
   * Marks metadata with annotations that let Prometheus know how to retrieve metrics from the
   * wls-exporter web-app. The specified httpPort should be the listen port of the WebLogic server
   * running in the pod.
   *  @param meta Metadata
   * @param applicationContext the context for the exporter application
   * @param httpPort HTTP listen port
   */
  static void annotateForPrometheus(V1ObjectMeta meta, String applicationContext, int httpPort) {
    meta.putAnnotationsItem(
        "prometheus.io/port", "" + httpPort); // should be the ListenPort of the server in the pod
    meta.putAnnotationsItem("prometheus.io/path", applicationContext + "/metrics");
    meta.putAnnotationsItem("prometheus.io/scrape", "true");
  }

  public static V1Pod withSha256Hash(V1Pod pod) {
    return DEBUG ? addHashAndDebug(pod) : addHash(pod);
  }

  public static V1Service withSha256Hash(V1Service service) {
    return addHash(service);
  }

  public static V1beta1PodDisruptionBudget withSha256Hash(V1beta1PodDisruptionBudget pdb) {
    return addHash(pdb);
  }

  private static V1Pod addHashAndDebug(V1Pod pod) {
    String dump = Yaml.dump(pod);
    addHash(pod);
    pod.getMetadata().putAnnotationsItem(HASHED_STRING, dump);
    return pod;
  }

  private static V1Pod addHash(V1Pod pod) {
    pod.getMetadata().putAnnotationsItem(SHA256_ANNOTATION, HASH_FUNCTION.apply(pod));
    return pod;
  }

  private static V1Service addHash(V1Service service) {
    service.getMetadata().putAnnotationsItem(SHA256_ANNOTATION, HASH_FUNCTION.apply(service));
    return service;
  }

  private static V1beta1PodDisruptionBudget addHash(V1beta1PodDisruptionBudget pdb) {
    pdb.getMetadata().putAnnotationsItem(SHA256_ANNOTATION, HASH_FUNCTION.apply(pdb));
    return pdb;
  }

  static String getHash(V1Pod pod) {
    return getAnnotation(pod.getMetadata(), AnnotationHelper::getSha256Annotation);
  }

  static String getHash(V1Service service) {
    return getAnnotation(service.getMetadata(), AnnotationHelper::getSha256Annotation);
  }

  static String getDebugString(V1Pod pod) {
    return getAnnotation(pod.getMetadata(), AnnotationHelper::getDebugHashAnnotation);
  }

  private static String getAnnotation(
      V1ObjectMeta metadata, Function<Map<String, String>, String> annotationGetter) {
    return Optional.ofNullable(metadata)
        .map(V1ObjectMeta::getAnnotations)
        .map(annotationGetter)
        .orElse("");
  }

  private static String getDebugHashAnnotation(Map<String, String> annotations) {
    return annotations.get(HASHED_STRING);
  }

  private static String getSha256Annotation(Map<String, String> annotations) {
    return annotations.get(SHA256_ANNOTATION);
  }
}