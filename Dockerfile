FROM kbase/condor-worker AS build
# Multistage Build Setup
ARG BUILD_DATE
ARG VCS_REF
ARG BRANCH=develop
COPY . /njs/
RUN echo "About to build $BRANCH" &&  cd /njs && ./gradlew buildAll

FROM kbase/condor-worker
# Copy configs for dockerize
RUN rm -rf /kb/deployment/
COPY --chown=kbase deployment/ /kb/deployment/

# Copy War and Fat Jar into root.war and for distribution to the worker nodes in /kb/deployment/lib
COPY --from=build /njs/dist/NJSWrapper.war /kb/deployment/jettybase/webapps/root.war
COPY --from=build /njs/dist/NJSWrapper-all.jar /kb/deployment/lib/
