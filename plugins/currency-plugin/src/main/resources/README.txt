#!/bin/sh

# In order to generate the Jooq classes either copy and paste the code below or run this through "sh" like:
# sh ./src/main/resources/README.txt

# Load the DDL schema in MySQL
DDL_DIR="`dirname \"$0\"`"
mysql -u root -proot killbill < "${DDL_DIR}"/ddl.sql

# Download the required jars
MVN_DOWNLOAD="mvn org.apache.maven.plugins:maven-dependency-plugin:3.5.0:get -DremoteRepositories=https://repo.maven.apache.org/maven2"
JOOQ_VERSION=3.15.2
eval $MVN_DOWNLOAD -Dartifact=org.jooq:jooq:$JOOQ_VERSION:jar
eval $MVN_DOWNLOAD -Dartifact=org.jooq:jooq-meta:$JOOQ_VERSION:jar
eval $MVN_DOWNLOAD -Dartifact=org.jooq:jooq-codegen:$JOOQ_VERSION:jar
REACTIVE_STREAM_VERSION=1.0.3
eval $MVN_DOWNLOAD -Dartifact=org.reactivestreams:reactive-streams:$REACTIVE_STREAM_VERSION:jar
R2DBC_VERSION=1.0.0.RELEASE
eval $MVN_DOWNLOAD -Dartifact=io.r2dbc:r2dbc-spi:$R2DBC_VERSION:jar
JAXB_VERSION=2.3.3
eval $MVN_DOWNLOAD -Dartifact=jakarta.xml.bind:jakarta.xml.bind-api:$JAXB_VERSION:jar
MYSQL_VERSION=8.0.30
eval $MVN_DOWNLOAD -Dartifact=mysql:mysql-connector-java:$MYSQL_VERSION:jar

M2_REPOS=~/.m2/repository
JOOQ="$M2_REPOS/org/jooq/jooq/$JOOQ_VERSION/jooq-$JOOQ_VERSION.jar:$M2_REPOS/org/jooq/jooq-meta/$JOOQ_VERSION/jooq-meta-$JOOQ_VERSION.jar:$M2_REPOS/org/jooq/jooq-codegen/$JOOQ_VERSION/jooq-codegen-$JOOQ_VERSION.jar"
REACTIVE_STREAMS="$M2_REPOS/org/reactivestreams/reactive-streams/$REACTIVE_STREAM_VERSION/reactive-streams-$REACTIVE_STREAM_VERSION.jar"
R2DBC="$M2_REPOS/io/r2dbc/r2dbc-spi/$R2DBC_VERSION/r2dbc-spi-$R2DBC_VERSION.jar"
JAXB="$M2_REPOS/jakarta/xml/bind/jakarta.xml.bind-api/$JAXB_VERSION/jakarta.xml.bind-api-$JAXB_VERSION.jar"
MYSQL="$M2_REPOS/mysql/mysql-connector-java/$MYSQL_VERSION/mysql-connector-java-$MYSQL_VERSION.jar"
JARS="$JOOQ:$REACTIVE_STREAMS:$R2DBC:$JAXB:$MYSQL:.";

# Run jOOQ's generation tool
java -cp $JARS org.jooq.codegen.GenerationTool ./src/main/resources/gen.xml
