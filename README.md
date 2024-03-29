# eXgit
JGit-based plugin for eXist-DB

provides XQuery access to git

Currently, this is in an early beta state. **Use at your own risk!** Especially, test on a non-productive system before anything else. Please report all issues you may find.

## Build from Source

1. clone the Repo
2. cd into repo's rootdirectory
3. run `mvn clean package`

## Install
### Option 1: use the xar
If maven runs successfully, the project root should contain a file named `exgit.xar` which can be deployed directly via the package manager.
If this does not work, try option 2.


### Option 2: copy and install manually
The packaged version includes all third-party JARs necessary to run eXgit. By using it you agree to the licenses under which these software packages are published;
all licenses can be found in `THIRD-PARTY.TXT`.

#### eXist < 5.0

1. copy `target/exgit-full.jar` into your exist-db home directory `$eXist-db HOME$/lib/user`
2. register eXgit module in `$exist-db HOME$/conf.xml`. Within `exist/xquery/builtin-modules` add:
    `<module uri="http://exist-db.org/xquery/exgit" class="org.exist.xquery.modules.exgit.Exgit"/>`
1. restart eXist.

#### eXist >= 5.0

1. copy `target/exgit-full.jar` to `$eXist-db HOME$/lib`
1. edit the configuration file for you startup script (e.g. `etc/startup.xml` for `bin/startup.sh/.bat`) and add:
    ```
    <dependency>
       <groupId>org.exist.xquery.modules.exgit</groupId>
       <artifactId>exgit</artifactId>
       <version>0.1</version>
       <relativePath>exgit-full.jar</relativePath>
    </dependency>
    ```
1. (re-)start eXist and check `logs/exist.log` for any information
1. if that does not work, try visiting https://github.com/eXist-db/documentation/issues/385 to see if any new info on how to load .jar has come up

`target/exgit.jar` does not contain any dependencies; you are responsible to provide all dependencies for the module to work.

## Usage
The very first step is to import the module:

    import module namespace exgit="http://exist-db.org/xquery/exgit" at "java:org.exist.xquery.modules.exgit.Exgit";

If all went well, eXide should be offering content completion while eXist's function documentation should contain
a complete documentation of the module (mind you that it might be necessary to rebuild the documentation after adding
or upgrading the module).

Currently, the following functions are available:

1. `exgit:export($repoDir, $collection)` – write `$collection` from the database to the local git repo `$repodir`
1. `exgit:commit($repoDir, $message)` – equal to `git commit -a -m'$message'` invoked in  `$repodir`
1. `exgit:commit($repoDir, $message, $authorName, $authorMail)` – same as above, explicitly setting the author
1. `exgit:push($repoDir, $remote, $username, $password)` – push to `$remote` (e.g. 'origin') supplying your user credentials
1. `exgit:pull($repoDir, $remote, $username, $password)` – pull from `$remote` (e.g. 'origin') supplying your user credentials
1. `exgit:import($repoDir, $collection)` – read XML, CSS, XQuery and JS from the repo and store them in the given collection
1. `exgit:clone($repo, $repoDir)` – clone a remote $repo into local $repoDir
1. `exgit:clone($repo, $repoDir, $branch)` – clone $branch from remote $repo into local $repoDir
1. `exgit:clone($repo, $repoDir, $branch, $username, $password)` – clone $branch from remote $repo into local $repoDir with the given credentials
1. `exgit:checkout($repoDir, $commit)` – checkout $commit from local $repoDir
1. `exgit:tags($repository)` – list all tags in $repository; if it starts with “http” or “ssh“, a list of tags in the remote repo ist returned; else, it is assumed to point to a local repo and a fetch is executed prior to listing
1. `exgit:info($repoDir, $commit)` – show git info for $commit in $repoDir
1. `exgit:exportFiles($repoDir, $collection, $files as xs:anyURI+)` – export resources in $files (paths must be relative to $collection) from $collection to $repoDir
1. `exgit:exportFiltered($repoDir, $collection, $regex)` – export resources from $collection to $repoDir that match $regex (full match!)

### Example script - to run in eXide

```XQuery
xquery version "3.1";
import module namespace exgit="http://exist-db.org/xquery/exgit" at "java:org.exist.xquery.modules.exgit.Exgit";

let $repoDir := "C:\Users\user\Desktop\gittest"
let $collection := "/db/apps/dsebaseapp/data"
let $export := exgit:export($repoDir, $collection)
let $message := "changes in data"
let $commit := exgit:commit($repoDir, $message)
let $push := exgit:push($repoDir, "origin", "username", "password")
let $pull := exgit:pull($repoDir, "origin", "username", "password")
let $import := exgit:import($repoDir, $collection)

return $import
```
### Example: check out a specific tag
```XQuery
let $co := exgit:checkout('/home/user/git/my-repo', 'refs/tags/my-tag')
```

## Error Codes
| code | meaning |
|--|--|
| **0xx** | **errors managing local repo** |
| 000 | No such file or directory<br>Nothing was found matching the given path |
| 003 | Not an absolute path<br>The path given is not an absolute path |
| 010 | No such directory<br>The given path points to a file |
| 011 | Not writable<br>The given path exits and is a directory but permission was denied to write for the given path |
| 022 | I/O error: cannot create directory<br>The given path does not exist and an I/O error occurred when trying create the given directory|
| 030 | No Repository<br>The given path exists, is not empty and not a valid git repository |
| 032 | I/O error checking for repo<br>The given path exists, is non empty but an I/O error occurred trying to check whether it is a git repo |
| | |
| **1xx** | **collection errors** |
| *10x* | *errors reading from a collection* |
| 100 | collection not found |
| 101 | permission denied reading from an existing collection |
| *11x* | *errors creating a collection* |
| 111 | Permission denied creating collection |
| 119 | General error creating collection |
| *12x* | *errors writing to a collection* |
| 121 | Permission denied creating a write lock for collection |
| 2xx | errors writing to disc |
| | |
| **3xx** | **errors raised by file operations on repositories** |
| *30x* | *errors committing* |
| 303 | Wrong repository state |
| 304 | No head |
| 305 | Unmerged Paths |
| 309 | Git API Error on commit<br>A general API errors was raised; see the message for details|
| *31x* | *errors pushing* |
| 313 | Invalid remote |
| 314 | Transport error |
| 319 | Git API error pushing<br>A general API errors was raised; see the message for details|
| *32x* | *errors pulling* |
| 323 | Invalid remote |
| 324 | Transport error |
| 329 | Git API error pulling<br>A general API errors was raised; see the message for details|
| *35x* | *errors cloning* |
| 353 | Invalid remote<br>The URL given does not point to a valid remote repository |
| 359 | Git API error on clone<br>A general API errors was raised; see the message for details|
| *36x* | *errors checking out* |
| 360 | Requested ref not found <br>The ref that was specified in the request could not be found in the repo|
| 363 | Checkout conflict<br>There was a conflict trying to checkout the specified ref|
| 369 | Git API Error on checkout<br>A general API errors was raised; see the message for details|
| | |
| **4xx** | **errors raised by metadata operations on repositories** |
| *40x* | *errors fetching tags* |
| 409 | Git API Error fetching tags<br>A general API errors was raised; see the message for details|
| *41x* | *errors getting repo info* |
| 419 | Git API Error getting repo info<br>A general API errors was raised; see the message for details|
| | |
| **5xx** | **errors from file operations** |
| 500 | The Path specified was not found |
| 501 | Permission denied accessing the specified collection |
| *51x* | *errors when preparing to write into a collection* |
| 511 | Permission denied trying to write to the specified collection |
| 513 | Error acquiring an exclusive lock to the specified collection |
| 519 | General error trying to create the specified collection |
| 522 | I/O error reading from the specified input path |
| *53x* | *errors writing XML files to collection* |
| 531 | Permission denied writing XML file to collection |
| 539 | A general error has occurred trying to store an XML file |
| *54x* | *errors writing binary files to collection* |
| 541 | Permission denied writing binary file to collection |
| 549 | General error trying to store a binary file
| 550 | File not found in directory when ingesting a file |
| 552 | I/O error when ingesting a file form directory |
| **6xx** | **file validation errors** |
| *60x* | *XML file validation errors* |
| 608 | Validation error for XML file |
| 609 | General error validating XML file |
| *61x* | *binary file validation errors* |
| 619 | General error validating binary file |

## Caveats and future
Currently, HTTP is used as transport and the user credentials have to be supplied within the XQuery.
One of the next steps is to a) add SSH as a mode of transport and b) support to store credentials
or an SSH key within the collection configuration so that sensitive information does not have to be
included in the XQuery as plain text.

## Legal Notice

In the fully packed ('shaded') jar ('-with-dependencies.jar'), third party software is included.  All licenses can be found in `THIRD-PARTY.TXT`.
