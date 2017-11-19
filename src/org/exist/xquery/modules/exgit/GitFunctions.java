package org.exist.xquery.modules.exgit;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.xmldb.api.base.*;
import org.xmldb.api.modules.*;
import org.xmldb.api.*;

import java.io.File;
import java.io.IOException;

import javax.xml.transform.OutputKeys;
import org.exist.xmldb.EXistResource;

import org.exist.dom.QName;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.Dependency;
import org.exist.xquery.ErrorCodes.ErrorCode;
//import org.exist.xquery.Function;
import org.exist.xquery.FunctionSignature;
//import org.exist.xquery.Profiler;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.xmldb.XMLDBGetChildCollections;
import org.exist.xquery.functions.xmldb.XMLDBGetChildResources;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

public class GitFunctions extends BasicFunction {
	private static String URI = "xmldb:exist://localhost:8080/exist/xmlrpc";

	public final static FunctionSignature signature[] = {
			new FunctionSignature(new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute a git commit.",
					new SequenceType[] {
							new FunctionParameterSequenceType("repoDir", Type.STRING,
									Cardinality.EXACTLY_ONE, "The path to the local repo"),
							new FunctionParameterSequenceType("message", Type.STRING,
									Cardinality.EXACTLY_ONE, "The commit message."),
							new FunctionParameterSequenceType("remote", Type.STRING,
									Cardinality.EXACTLY_ONE, "The remote."),
							new FunctionParameterSequenceType("usernae", Type.STRING,
									Cardinality.EXACTLY_ONE, "The user for authentication."),
							new FunctionParameterSequenceType("password", Type.STRING,
									Cardinality.EXACTLY_ONE, "The remote password.")
							},
					new FunctionReturnSequenceType(Type.DOUBLE, Cardinality.EXACTLY_ONE, "the commit hash.")),
			new FunctionSignature(new QName("push", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute git push.", null,
					new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE, "the reply.")) };

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		String functionName = getSignature().getName().getLocalPart();
		ValueSequence result = new ValueSequence();

		// initialize database driver
		try {
			Database database = (Database) org.exist.xmldb.DatabaseImpl.class.newInstance();
			database.setProperty("create-database", "true");
			DatabaseManager.registerDatabase(database);
		} catch (Exception dbe) {
			throw new XPathException(new ErrorCode("exgit01", "XMLDB error"), dbe.toString());
		}
		
		// TODO load configuration from a file in the database
		// Should this be a configuration file in /db/system or should it be stored in the collection?
		// initialize the git repo
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		
		String dirpath = args[0].toString();
		String message = args[1].toString();
		String remotes = args[2].toString();
		String username = args[3].toString();
		String password = args[4].toString();
		
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

		switch (functionName) {
		case "commit":
			//result.addAll(list(args[0].toString()));
			Git git = new Git(repository);
			
			String status;
			String mod;
			RevCommit c;
			try {
				Status stat = git.status().call();
				status = stat.getChanged().toString();
				mod = stat.getModified().toString();
				git.add().addFilepattern(".").call();
				c = git.commit().setMessage(message).call();
			} catch (Exception e) {
				throw new XPathException(new ErrorCode("exgit00a", "Git API Error: " + git.toString()), e.toString());
			}
			result.add(new StringValue(args[0].toString()));
			result.add(new StringValue(path));
			result.add(new StringValue(status));
			result.add(new StringValue(mod));
			result.add(new StringValue(c.getId().toString()));
			result.add(new StringValue(c.getAuthorIdent().toString()));
			Iterable<PushResult> p;
			try {
				p = git.push().setRemote(remotes)
						.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password)).call();
			} catch (GitAPIException e) {
				// TODO Auto-generated catch block
				throw new XPathException(new ErrorCode("exgit03", "Git Push error: " + git.toString()), e.toString());
			}
			
			result.add(new StringValue(p.iterator().next().getMessages()));
			/* TODO somehow store within the collection that it belongs to a git repo and which repo that is
			 * and where that repo  is to be found on the file system
			 */
			// TODO export the files in the collection to the external directory to invoke the git magic
			break;
		case "push":
			result.add(new StringValue("okay"));
			break;
		default:
			throw new XPathException(new ErrorCode("E01", "function not found"),
					"The requested function was not found in this module");
		}

		return result;
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
			//result.add(new StringValue(col.listResources()));
		} catch (XMLDBException e) {
			throw new XPathException(new ErrorCode("exgit03", e.toString()), e.toString());
		}
		
		return result;
	}
}
