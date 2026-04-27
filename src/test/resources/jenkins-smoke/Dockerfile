FROM jenkins/jenkins:2.516.1-lts-jdk17

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

COPY --chown=jenkins:jenkins init.groovy.d/jenkins-notifier-smoke.groovy.template /usr/share/jenkins/ref/init.groovy.d/jenkins-notifier-smoke.groovy