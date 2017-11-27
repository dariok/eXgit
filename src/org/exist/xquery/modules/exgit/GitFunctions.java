package org.exist.xquery.modules.exgit;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.exist.dom.QName;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.storage.serializers.Serializer;
import org.exist.util.LockException;
import org.exist.util.serializer.SAXSerializer;
import org.exist.util.serializer.SerializerPool;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.modules.file.*;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes.ErrorCode;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;
import org.xml.sax.SAXException;
import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.XMLDBException;

import com.siemens.ct.exi.values.BooleanValue;

public class GitFunctions extends BasicFunction {
	private static String URI = "xmldb:exist:///db/";

	public final static FunctionSignature signature[] = {
			new FunctionSignature(new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute a git commit.",
					new SequenceType[] {
							new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
									"The full path to the local git repository"),
							new FunctionParameterSequenceType("message", Type.STRING, Cardinality.EXACTLY_ONE,
									"The commit message.") },
					new FunctionReturnSequenceType(Type.DOUBLE, Cardinality.EXACTLY_ONE, "the commit hash.")),
			new FunctionSignature(new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute a git commit.",
					new SequenceType[] {
							new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
									"The full path to the local git repository"),
							new FunctionParameterSequenceType("message", Type.STRING, Cardinality.EXACTLY_ONE,
									"The commit message."),
							new FunctionParameterSequenceType("authorName", Type.STRING, Cardinality.EXACTLY_ONE,
									"The name to set as author."),
							new FunctionParameterSequenceType("authorMail", Type.STRING, Cardinality.EXACTLY_ONE,
									"The eMail to set for the author.")},
					new FunctionReturnSequenceType(Type.DOUBLE, Cardinality.EXACTLY_ONE, "the commit hash.")),
			new FunctionSignature(new QName("push", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute git push.",
					new SequenceType[] {
							new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
									"The full path to the local git repository"),
							new FunctionParameterSequenceType("remote", Type.STRING, Cardinality.EXACTLY_ONE,
									"The remote."),
							new FunctionParameterSequenceType("username", Type.STRING, Cardinality.EXACTLY_ONE,
									"The user for authentication."),
							new FunctionParameterSequenceType("password", Type.STRING, Cardinality.EXACTLY_ONE,
									"The remote password.") },
					new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE, "the reply.")),
			new FunctionSignature(new QName("sync", Exgit.NAMESPACE_URI, Exgit.PREFIX),
					"Synchronize a collection with a directory hierarchy. Compares last modified time stamps. "
							+ "If $dateTime is given, only resources modified after this time stamp are taken into account. "
							+ "This method is only available to the DBA role.",
					new SequenceType[] {
							new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
									"The full path to the local git repository"),
							new FunctionParameterSequenceType("collection", Type.STRING, Cardinality.EXACTLY_ONE,
									"The collection to sync.")/*,
							new FunctionParameterSequenceType("dateTime", Type.DATE_TIME, Cardinality.ZERO_OR_ONE,
									"Optional: only resources modified after the given date/time will be synchronized.")*/ },
					new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
							"true if successful, false otherwise")) };

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		String functionName = getSignature().getName().getLocalPart();
		ValueSequence result = new ValueSequence();

		// initialize database driver
		/*try {
			Database database = (Database) org.exist.xmldb.DatabaseImpl.class.newInstance();
			database.setProperty("create-database", "true");
			DatabaseManager.registerDatabase(database);
		} catch (Exception dbe) {
			throw new XPathException(new ErrorCode("exgit01", "XMLDB error"), dbe.toString());
		}*/
		
		/* TODO somehow store within the collection that it belongs to a git repo and which repo that is
		 * and where that repo  is to be found on the file system
		 */
		// TODO load configuration from a file in the database
		// Should this be a configuration file in /db/system or should it be stored in the collection?
		// initialize the git repo
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		
		String dirpath = args[0].toString();
		
		Repository repository;
		File repo;
		String path;
		try {
			repo = new File(dirpath);
			path = repo.getCanonicalPath();
			repository = builder.findGitDir(repo).readEnvironment().build();
		} catch (IOException e) {
			throw new XPathException(new ErrorCode("exgit00", "I/O Error: " + dirpath), e.toString());
		}
		
		Git git = new Git(repository);

		switch (functionName) {
		case "commit":
			String message = args[1].toString();
			
			String status;
			String mod;
			RevCommit c;
			try {
				Status stat = git.status().call();
				status = stat.getChanged().toString();
				mod = stat.getModified().toString();
				git.add().addFilepattern(".").call();
				
				if (args.length == 2) {
					c = git.commit().setMessage(message).call();
				} else {
					c = git.commit().setMessage(message).setAuthor(args[2].toString(), args[3].toString()).call();
				}
			} catch (Exception e) {
				throw new XPathException(new ErrorCode("exgit00a", "Git API Error: " + git.toString()), e.toString());
			} finally {
				git.close();
			}
			result.add(new StringValue(args[0].toString()));
			result.add(new StringValue(path));
			result.add(new StringValue(status));
			result.add(new StringValue(mod));
			result.add(new StringValue(c.getId().toString()));
			result.add(new StringValue(c.getAuthorIdent().toString()));
			
			break;
		case "push":
			String remotes = args[1].toString();
			String username = args[2].toString();
			String password = args[3].toString();
			Iterable<PushResult> p;
			try {
				p = git.push().setRemote(remotes)
						.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password)).call();
			} catch (GitAPIException e) {
				// TODO Auto-generated catch block
				throw new XPathException(new ErrorCode("exgit03", "Git Push error: " + git.toString()), e.toString());
			} finally {
				git.close();
			}
			
			result.add(new StringValue(p.iterator().next().getMessages()));
			break;
		case "sync":
			/*Sequence nargs[] = {args[1], args[0], args[2]}; 
			Sync sync = new Sync(context);
			sync.eval(nargs, contextSequence);*/
			result.add(new StringValue(syncCollection(args[0].toString(), args[1].toString())));
			break;
		case "pull":
			// TODO ggf. high level functionen anbieten
		case "import":
			// TODO vorher prüfen, ob wohlgeformt
		default:
			git.close();
			throw new XPathException(new ErrorCode("E01", "function not found"),
					"The requested function was not found in this module");
		}
		
		git.close();
		
		return result;
	}
	
	/* error codes 2xy */
	private String syncCollection(String pathToLocal, String pathToCollection) throws XPathException {
		Path repo = getRepo(pathToLocal);
		org.exist.collections.Collection collection = getCollection(pathToCollection);
		
		Iterator<DocumentImpl> documents;
		Iterator<XmldbURI> subcollections;
		try {
			documents = collection.iterator(context.getBroker());
			subcollections = collection.collectionIterator(context.getBroker());
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit201", "permission denied"),
					"Access to the requested collection's contents was denied.");
		} catch (LockException e) {
			throw new XPathException(new ErrorCode("exgit202", "locking error"),
					"Failed to obtain a lock on " + collection.getURI().toString() + ".");
		}
		
		while (documents.hasNext()) {
			DocumentImpl doc = documents.next();
			Serializer serializer = context.getBroker().getSerializer();
			
			Path filePath = Paths.get(repo.toString(), doc.getFileURI().toString());
			
			Writer writer;
			try {
				writer = new OutputStreamWriter(Files.newOutputStream(filePath), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new XPathException(new ErrorCode("exgit211", "unknown encoding"),
						"The JRE does not know UTF-8; sth very strange is going on.");
			} catch (IOException e) {
				throw new XPathException(new ErrorCode("exgit212", "I/O error"),
						"Cannot write to " + filePath.toString() + ".");
			}
			
			try {
				serializer.serialize(doc, writer);
			} catch (SAXException e) {
				throw new XPathException(new ErrorCode("exgit213", "serializer exception"),
						"Serializing " + filePath.toString() + " failed: " + e.getLocalizedMessage() + ".");
			}
		}
		
		while (subcollections.hasNext()) {
			XmldbURI coll = subcollections.next();
			
			syncCollection(coll.toString(), pathToCollection);
		}
		
		return collection.getURI().toString();
	}
	
	/* exception codes 11x */
	private org.exist.collections.Collection getCollection(String pathToCollection) throws XPathException {
		org.exist.collections.Collection collection;
		
		try {
			collection = context.getBroker().openCollection(XmldbURI.create(pathToCollection), LockMode.READ_LOCK);
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit111", "permission denied"),
					"Access to the requested collection was denied.");
		}
		
		if (collection == null) {
			throw new XPathException(new ErrorCode("exgit111", "collection not found"),
					"The requested collection was not found.");
		}
		
		return collection;
	}
	
	/* exception codes 10x */
	private Path getRepo (String pathToLocal) throws XPathException {
		Path repo = Paths.get(pathToLocal);
		
		if (!Files.isDirectory(repo))
				throw new XPathException(new ErrorCode("exgit101", "no such directory"),
						"The requested local repositoy was not found under the given path");
		if (!Files.isWritable(repo))
			throw new XPathException(new ErrorCode("exgit102", "not writable"),
					"The requested local repository is not writable");
		
		return repo;
	}
	
	private Sequence list(String collection) throws XPathException {
		ValueSequence result = new ValueSequence();
		Collection col;

		try {
			col = DatabaseManager.getCollection(URI + collection);
		} catch (Exception e2) {
			throw new XPathException(new ErrorCode("exgit02", "Error getting coll.: " + e2.toString()), e2.toString());
		}

		// now we have a list of all the documents in our given collection
		try {
			for (String file : col.listResources()) {
				result.add(new StringValue(collection + '/' + file));
			}
			for (String coll : col.listChildCollections()) {
				result.add(new StringValue("Collection: " + collection + '/' + coll));
				result.addAll(list(collection + '/' + coll));
			}
			// result.add(new StringValue(col.listResources()));
		} catch (XMLDBException e) {
			throw new XPathException(new ErrorCode("exgit03", e.toString()), e.toString());
		}

		return result;
	}
}
