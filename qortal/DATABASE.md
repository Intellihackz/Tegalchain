# Database

You can examine your node's database using [HSQLDB's "sqltool"](http://www.hsqldb.org/doc/2.0/util-guide/sqltool-chapt.html).
It's a good idea to install "rlwrap" (ReadLine wrapper) too as sqltool doesn't support command history/editing.

Typical command line for sqltool would be:
`rlwrap java -cp ${HSQLDB_JAR}:${SQLTOOL_JAR} org.hsqldb.cmdline.SqlTool --rcFile=${SQLTOOL_RC} qora`

`${HSQLDB_JAR}` should be set with pathname where Maven downloaded hsqldb, 
typically `${HOME}/.m2/repository/org/hsqldb/hsqldb/2.5.0/hsqldb-2.5.0.jar`

`${SQLTOOL_JAR}` should be set with pathname where Maven downloaded sqltool,
typically  `${HOME}/.m2/repository/org/hsqldb/sqltool/2.5.0/sqltool-2.5.0.jar`

`${SQLTOOL_RC}` should be set with pathname of a text file describing HSQLDB databases, 
e.g. `${HOME}/.sqltool.rc`, with contents like:

```
urlid qortal
url jdbc:hsqldb:file:db/blockchain
username SA
password
```
Above `url` component `file:db/blockchain` assumes you will call `sqltool` from directory containing `db/`.

Another idea is to assign a shell alias in your `.bashrc` like:
```
export HSQLDB_JAR=${HOME}/.m2/repository/org/hsqldb/hsqldb/2.5.0/hsqldb-2.5.0.jar
export SQLTOOL_JAR=${HOME}/.m2/repository/org/hsqldb/sqltool/2.5.0/sqltool-2.5.0.jar
alias sqltool='rlwrap java -cp ${HSQLDB_JAR}:${SQLTOOL_JAR} org.hsqldb.cmdline.SqlTool --rcFile=${SQLTOOL_RC}'
```
So you can simply type: `sqltool qortal`

Don't forget to use `SHUTDOWN;` before exiting sqltool so that database files are closed cleanly.
