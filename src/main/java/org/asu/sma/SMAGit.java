package org.asu.sma;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Wrapper for git interactions using jGit.
 * @author aesanch2
 */
public class SMAGit
{
    private Git git;
    private Repository repository;
    private ArrayList<String> additions, deletions, modificationsOld, modificationsNew, contents;
    private String prevCommit, curCommit;

    private static final Logger LOG = Logger.getLogger(SMAGit.class.getName());

    /**
     * Creates an SMAGit instance for the initial commit and/or initial build. (no previous commit)
     * @param pathToRepo The path to the git repository.
     * @param curCommit The current commit.
     * @throws Exception
     */
    public SMAGit(String pathToRepo, String curCommit) throws Exception
    {
        File repoDir = new File(pathToRepo);
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().build();
        git = new Git(repository);
        this.curCommit = curCommit;

        //Since this is an initial commit everything will be added
        additions = getContents();
    }

    /**
     * Creates an SMAGit instance for all other builds. (previous commit known)
     * @param pathToRepo The path to the git repository.
     * @param curCommit The current commit.
     * @param prevCommit The previous commit.
     * @throws Exception
     */
    public SMAGit(String pathToRepo, String curCommit, String prevCommit) throws Exception
    {
        File repoDir = new File(pathToRepo);
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repository = builder.setGitDir(repoDir).readEnvironment().build();
        git = new Git(repository);
        this.prevCommit = prevCommit;
        this.curCommit = curCommit;

        //Determine what's changed between last and current commit
        determineChanges();
    }

    /**
     * Returns all of the items that were added in the current commit.
     * @return The ArrayList containing all of the additions in the current commit.
     * @throws IOException
     */
    public ArrayList<String> getAdditions() throws IOException
    {
        if (additions == null)
        {
            additions = new ArrayList<String>();
        }
        return additions;
    }

    /**
     * Returns all of the items that were deleted in the current commit.
     * @return The ArrayList containing all of the items that were deleted in the current commit.
     */
    public ArrayList<String> getDeletions()
    {
        if (deletions == null)
        {
            deletions = new ArrayList<String>();
        }
        return deletions;
    }

    /**
     * Returns all of the updated changes in the current commit.
     * @return The ArrayList containing the items that were modified (new paths) and added to the repository.
     * @throws IOException
     */
    public ArrayList<String> getNewChangeSet() throws IOException
    {
        ArrayList<String> newChangeSet = new ArrayList<String>();

        //If we have no previous commit, then everything has changed
        if (prevCommit == null)
        {
            newChangeSet = getContents();
        }
        //Otherwise, add all the additions and the changed files (new path)
        else
        {
            newChangeSet.addAll(additions);
            newChangeSet.addAll(modificationsNew);
        }

        return newChangeSet;
    }

    /**
     * Returns all of the deleted or modified (old paths) changes in the current commit.
     * @return ArrayList containing the items that were modified (old paths) and deleted from the repository.
     */
    public ArrayList<String> getOldChangeSet()
    {
        //Add all the deletions and changed (old paths)
        ArrayList<String> oldChangeSet = new ArrayList<String>();
        oldChangeSet.addAll(deletions);
        oldChangeSet.addAll(modificationsOld);

        return oldChangeSet;
    }


    /**
     * Checks out the previous commit's files that were modified or deleted and copies them to the rollback stage.
     * @param destDir The location of the rollback stage.
     * @throws Exception
     */
    public void getPrevCommitFiles(ArrayList<SMAMetadata> members, String destDir) throws Exception
    {
        ObjectId prevCommitId = repository.resolve(prevCommit);

        RevWalk revWalk = new RevWalk(repository);
        RevCommit commit = revWalk.parseCommit(prevCommitId);
        RevTree tree = commit.getTree();
        TreeWalk treeWalk;

        for(SMAMetadata file : members)
        {
            treeWalk = new TreeWalk(repository);
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);
            String fullName = file.getPath() + file.getFullName();
            treeWalk.setFilter(PathFilter.create(fullName));
            if(!treeWalk.next())
            {
                throw new IllegalStateException("Did not find expected file '" +
                fullName + "'");
            }
            ObjectId objectId = treeWalk.getObjectId(0);
            ObjectLoader loader = repository.open(objectId);

            File destination = new File(destDir + "/" + file.getPath());
            if(!destination.exists())
            {
                destination.mkdirs();
            }
            File copy = new File(destDir + "/" + fullName);
            FileOutputStream fop = new FileOutputStream(copy);
            loader.copyTo(fop);
        }

        revWalk.dispose();
    }


    /**
     * Creates an updated package.xml file and commits it to the repository
     * @param workspace The workspace.
     * @param userName The user name of the committer.
     * @param userEmail The email of the committer.
     * @return A boolean value indicating whether an update was required or not.
     * @throws Exception
     */
    public boolean updatePackageXML(String workspace, String userName, String userEmail) throws Exception
    {
        //If we have either additions or deletions (i.e. any changes)
        if (!getAdditions().isEmpty() || !getDeletions().isEmpty())
        {
            SMAPackage packageManifest = new SMAPackage(workspace, getContents(), false);
            SMAManifestGenerator.generateManifest(packageManifest);

            //Commit the updated package.xml file to the repository
            git.add().addFilepattern("src/package.xml").call();
            git.commit().setCommitter(userName, userEmail).setMessage("Jenkins updated src/package.xml").call();

            return true;
        }
        return false;
    }

    /**
     * Replicates ls-tree for the current commit.
     * @return ArrayList containing the full path for all items in the repository.
     * @throws IOException
     */
    public ArrayList<String> getContents() throws IOException
    {
        contents = new ArrayList<String>();
        ObjectId commitId = repository.resolve(curCommit);
        RevWalk revWalk = new RevWalk(repository);
        RevCommit commit = revWalk.parseCommit(commitId);
        RevTree tree = commit.getTree();
        TreeWalk treeWalk = new TreeWalk(repository);
        treeWalk.addTree(tree);
        treeWalk.setRecursive(false);

        while(treeWalk.next())
        {
            if(treeWalk.isSubtree())
            {
                treeWalk.enterSubtree();
            }
            else
            {
                String member = treeWalk.getPathString();
                contents.add(member);
            }
        }

        return contents;
    }

    /**
     * Parses the diff between previous commit and current commit and sorts the changes into
     * lists that correspond to the change made.
     * @throws Exception
     */
    private void determineChanges() throws Exception
    {
        deletions = new ArrayList<String>();
        additions = new ArrayList<String>();
        modificationsNew = new ArrayList<String>();
        modificationsOld = new ArrayList<String>();

        String item;
        //Get all the diffs between prev and current commit
        List<DiffEntry> diffs = getDiffs();

        for (DiffEntry diff : diffs)
        {
            if (diff.getChangeType().toString().equals("DELETE"))
            {
                item = checkMeta(diff.getOldPath());
                if(!deletions.contains(item))
                {
                    deletions.add(item);
                }
            }
            else if (diff.getChangeType().toString().equals("ADD"))
            {
                additions.add(diff.getNewPath());
                item = checkMeta(diff.getNewPath());
                if(!additions.contains(item))
                {
                    additions.add(item);
                }
            }
            //If we modified it, get its old and new names and parse accordingly
            else if (diff.getChangeType().toString().equals("MODIFY"))
            {
                item = checkMeta(diff.getNewPath());
                if(!modificationsNew.contains(item))
                {
                    modificationsNew.add(item);
                }

                item = checkMeta(diff.getOldPath());
                if(!modificationsOld.contains(item))
                {
                    modificationsOld.add(item);
                }
            }
        }
    }

    /**
     * Returns the diff between two commits.
     * @return List that contains DiffEntry objects of the changes made between the previous and current commits.
     * @throws Exception
     */
    private List<DiffEntry> getDiffs() throws Exception
    {
        CanonicalTreeParser previousTree = getTree(prevCommit);
        CanonicalTreeParser newTree = getTree(curCommit);
        return git.diff().setOldTree(previousTree).setNewTree(newTree).call();
    }

    /**
     * Returns the Canonical Tree Parser  representation of a commit.
     * @param commit Commit in the repository.
     * @return CanonicalTreeParser representing the tree for the commit.
     * @throws IOException
     */
    private CanonicalTreeParser getTree(String commit) throws IOException
    {
        CanonicalTreeParser tree = new CanonicalTreeParser();
        ObjectReader reader = repository.newObjectReader();
        ObjectId head = repository.resolve(commit + "^{tree}");
        tree.reset(reader, head);
        return tree;
    }

    private static String checkMeta(String repoItem)
    {
        String actualItem = repoItem;

        if (repoItem.contains("-meta"))
        {
            actualItem = repoItem.substring(0, repoItem.length()-9);
        }

        return actualItem;
    }
}
