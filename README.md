# eXgit
JGit-based plugin for eXist-DB

provides XQuery access to git

# build from Source

1. clone the Repo
2. cd into repo's rootdirectory
3. run `mvn package`

# install

1. copy `target/exgit-0.1-jar-with-dependencies.jar` into your exist-db home directory `eXist-db HOME/lib/user`
2. register eXgit module in `exist-db HOME/conf.xml`:

Within `exist/xquery/builtin-modules`:

    <module uri="http://exist-db.org/xquery/exgit" class="org.exist.xquery.modules.exgit.Exgit"/>
