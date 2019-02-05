# Copyright 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.

FROM  oracle/weblogic:12213-patch-wls-for-k8s

ENV DOMAIN_NAME="domain1" \
    BUILD_TMP="${ORACLE_HOME}/docker-build-tmp" \
    WLS_CREDENTIALS="/weblogic-credentials"

ENV DOMAIN_HOME="${ORACLE_HOME}/user_projects/domains/${DOMAIN_NAME}"

ARG ENCODED_ADMIN_USERNAME
ARG ENCODED_ADMIN_PASSWORD

USER root

RUN mkdir -p ${DOMAIN_HOME} && \
    mkdir -p ${BUILD_TMP} && \
    mkdir -p ${WLS_CREDENTIALS} && \
    echo ${ENCODED_ADMIN_USERNAME} | base64 --decode > ${WLS_CREDENTIALS}/username && \
    echo ${ENCODED_ADMIN_PASSWORD} | base64 --decode > ${WLS_CREDENTIALS}/password

COPY model/model.yaml ${BUILD_TMP}
COPY weblogic-deploy.zip ${BUILD_TMP}

RUN cd ${BUILD_TMP} && \
    ${JAVA_HOME}/bin/jar xf weblogic-deploy.zip && \
    rm weblogic-deploy.zip && \
    chmod +x weblogic-deploy/bin/*.sh && \
    chmod -R +x weblogic-deploy/lib/python && \
    ${BUILD_TMP}/weblogic-deploy/bin/createDomain.sh \
    -oracle_home ${ORACLE_HOME} \
    -java_home ${JAVA_HOME} \
    -domain_home ${DOMAIN_HOME} \
    -domain_type WLS \
    -model_file ${BUILD_TMP}/model.yaml && \
    find ${DOMAIN_HOME} -type f | xargs ls -l && \
    cat ${DOMAIN_HOME}/config/config.xml && \
    cat ${DOMAIN_HOME}/security/DefaultAuthenticatorInit.ldift && \
    cat ${DOMAIN_HOME}/servers/admin-server/security/boot.properties && \
    echo "#/bin/bash" > bootstrap.sh && \
    echo "${DOMAIN_HOME}/startWebLogic.sh&" >> ${BUILD_TMP}/bootstrap.sh && \
    echo "sleep 120" >> ${BUILD_TMP}/bootstrap.sh && \
    chmod +x ${BUILD_TMP}/bootstrap.sh && \
    cat ${BUILD_TMP}/bootstrap.sh
RUN ${BUILD_TMP}/bootstrap.sh
RUN find ${DOMAIN_HOME} -type f | xargs ls -l && \
    cat ${DOMAIN_HOME}/security/DefaultAuthenticatorInit.ldift && \
    cat ${DOMAIN_HOME}/servers/admin-server/security/boot.properties && \
    rm -rf ${BUILD_TMP} && \
    rm -rf ${WLS_CREDENTIALS} && \
    chown -R oracle:oracle ${DOMAIN_HOME} && \
    chmod -R a+xwr ${DOMAIN_HOME}

USER oracle

WORKDIR ${DOMAIN_HOME}
