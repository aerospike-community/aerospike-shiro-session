## Aerospike Shiro Session DAO Example

This project is an Aerospike implementation of a [Shiro](http://shiro.apache.org/) [CachingSessionDAO](https://shiro.apache.org/static/1.2.4/apidocs/index.html?org/apache/shiro/session/mgt/eis/CachingSessionDAO.html) to show how to use an Aerospike cluster for an enterprise session store across multiple Tomcat servers. It is based on the [Shiro Web](https://github.com/apache/shiro/tree/master/samples/web) sample project.

### Configuration

The configuration for the project is done via the ```shiro.ini``` file in the ```WebContent\WEB-INF``` folder. These are the configuration parameters:

```
# Aerospike Session DAO configuration
# Session timeout expressed in milliseconds
sessionDAO.globalSessionTimeout = 1800000

# Namespace to use for the session store
sessionDAO.namespace = test

# Setname to use for the session store
sessionDAO.setname = sessions

# Hostname for the Aerospike cluster
sessionDAO.hostname = localhost

# Port for the Aerospike cluster
sessionDAO.port = 3000
```

### Building

This project requires [gradle](http://gradle.org/gradle-download/). After ensuring gradle has been installed, clone this repository and run ```gradle assemble``` from the command line. This will create an ```aerospike-shiro-session-1.0.war``` file in the ```build/libs``` directory.

### Deploy

To deploy the project, copy the ```aerospike-shiro-session-1.0.war``` file from the ```build/libs``` directory to the ```webapps``` directory for your Apache Tomcat server. This project has been tested with Tomcat version 8.0.32. Note that Tomcat version 8.0.35 has this [bug](https://bz.apache.org/bugzilla/show_bug.cgi?id=59566), so should be avoided.

### Usage

To use the application, point your browser to 

```http://localhost:8080/aerospike-shiro-session-1.0```
   
Click on the ```Log in``` link and enter ```user``` for the username field and ```password``` for the pasword field on the log in form. You will be redirected to the ```accounts``` page. This page requies the user to be authenticated (see the ```[urls]``` section in the ```shiro.ini``` file).

### Multiple Tomcat Servers

The configuration provided in this example does not use a session cache on the Tomcat server. This allows any Tomcat instance to serve the request as all servers will read/update the session information in the Aerospike DB. This means you do not need to configure "sticky sessions" on the load balancer. 

To test this out, configure another Tomcat server and deploy the ```aerospike-shiro-session-1.0.war``` to that server. Now you can point a browser window to each of the servers and try logging in/out from either Tomcat instance. Refreshing the other browser window will reflect the update as well.