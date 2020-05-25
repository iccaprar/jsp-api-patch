# Description

This repository exists solely for the development of the Tomcat's JSP API patch, needed to fix https://bz.apache.org/bugzilla/show_bug.cgi?id=57583 for more than the simple tag attributes used in EL.

# Usage

Build the distribution jar file using gradle jar, then add the new version of the file to the projects needing it.

The resulting JAR is placed in $CATALINA_BASE/lib, making use of Tomcat's class loading mechanism.

The common class loader contains additional classes that are made visible to both Tomcat internal classes and to all web applications.

The locations searched by this class loader are defined by the common.loader property in $CATALINA_BASE/conf/catalina.properties.

The default setting will search the following locations in the order they are listed:
- Unpacked classes and resources in $CATALINA_BASE/lib
- JAR files in $CATALINA_BASE/lib
- Unpacked classes and resources in $CATALINA_HOME/lib
- JAR files in $CATALINA_HOME/lib
