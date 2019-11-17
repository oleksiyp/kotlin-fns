
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.kohsuke.github.GHFileNotFoundException
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHubBuilder
import org.slf4j.LoggerFactory
import java.io.File

class GithubScope(
    val githubRepository: GHRepository,
    val localRepository: Git,
    val branchName: String,
    val baseName: String,
    val workDir: File,
    val repoCreated: Boolean
) {
    var commitMessage: String? = null
    var pullRequestMessage: String? = null
    var newRepoDescription: String? = null
    var repoDescription: String? = null
    var changedFiles: List<String>? = null
}

fun <T> github(
    org: String,
    repo: String,
    ghBuilder: GitHubBuilder = GitHubBuilder.fromCredentials(),
    branch: String? = null,
    base: String? = null,
    workDir: String? = null,
    mutateOrRead: GithubScope.() -> T
): T {
    val githubClient = ghBuilder.build()
    val logger = LoggerFactory.getLogger("devopslib.GithubFn")

    val credProvider = CredentialsFromGithub(ghBuilder)

    val scope = try {
        logger.info("Fetching info about repo {}/{}", org, repo)
        val remoteRepo = githubClient.getRepository("$org/$repo")

        val branchName = branch ?: remoteRepo.defaultBranch
        val baseName = base ?: remoteRepo.defaultBranch

        val workDirFile = File(workDir ?: "$org/$repo/$branchName")

        val localRepo =
            try {
                Git.open(workDirFile).apply {
                    logger.info("Opened existing repository at {}", workDirFile)
                    if (lsRemote()
                            .callAsMap()
                            .containsKey("origin")
                    ) {
                        remoteSetUrl()
                            .setRemoteName("origin")
                            .setRemoteUri(URIish(remoteRepo.httpTransportUrl))
                    } else {
                        remoteAdd()
                            .setName("origin")
                            .setUri(URIish(remoteRepo.httpTransportUrl))
                    }

                    logger.info("Resetting it")
                    reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef(branchName)
                        .call()

                    logger.info("Cleaning it")
                    clean()
                        .setForce(true)
                        .call()
                }
            } catch (ex: Exception) {
                logger.info("Cloning {} to {}", remoteRepo.httpTransportUrl, workDirFile)
                workDirFile.parentFile.mkdirs()

                try {
                    Git.cloneRepository()
                        .setCredentialsProvider(credProvider)
                        .setURI(remoteRepo.httpTransportUrl)
                        .setDirectory(workDirFile)
                        .call()
                } catch (ex2: Exception) {
                    logger.warn("Deleting working directory because of error: {}", ex.toString())
                    workDirFile.deleteRecursively()

                    logger.info("Cloning {} to {}", remoteRepo.httpTransportUrl, workDirFile)
                    Git.cloneRepository()
                        .setCredentialsProvider(credProvider)
                        .setURI(remoteRepo.httpTransportUrl)
                        .setDirectory(workDirFile)
                        .call()
                }
            }

        try {
            remoteRepo.getBranch(branchName)

            logger.info("Fetching $branchName")
            localRepo.fetch()
                .setCredentialsProvider(credProvider)
                .setRemote("origin")
                .setRefSpecs("refs/heads/$branchName:refs/remotes/origin/$branchName")
                .setForceUpdate(true)
                .call()

            localRepo.branchCreate()
                .setName(branchName)
                .setForce(true)
                .setStartPoint("refs/remotes/origin/$branchName")
                .call()

            logger.info("Checking out {}", branchName)
            localRepo.checkout()
                .setName(branchName)
                .call()

            localRepo.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef("refs/remotes/origin/$branchName")
                .call()

        } catch (ex: GHFileNotFoundException) {
            logger.info("Fetching $baseName")
            localRepo.fetch()
                .setCredentialsProvider(credProvider)
                .setRemote("origin")
                .setRefSpecs("refs/heads/$baseName:refs/remotes/origin/$baseName")
                .setForceUpdate(true)
                .call()

            localRepo.branchCreate()
                .setName(branchName)
                .setForce(true)
                .setStartPoint("refs/remotes/origin/$baseName")
                .call()

            logger.info("Checking out {} from {}", branchName, baseName)
            localRepo.checkout()
                .setName(branchName)
                .call()

            localRepo.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef("refs/remotes/origin/$baseName")
                .call()

        }


        GithubScope(
            remoteRepo,
            localRepo,
            branchName,
            baseName,
            workDirFile,
            false
        )
    } catch (ex: GHFileNotFoundException) {
        logger.info("Creating repository {}/{}", org, repo)
        val remoteRepo = if (githubClient.myself.login == org) {
            githubClient.createRepository(repo)
        } else {
            githubClient.getOrganization(org)
                .createRepository(repo)
        }.create()

        val branchName = branch ?: "master"
        val baseName = base ?: remoteRepo.defaultBranch
        val workDirFile = File(workDir ?: "$org/$repo/$branchName")

        workDirFile.parentFile.mkdirs()

        val localRepo = Git.init()
            .setDirectory(workDirFile)
            .call()

        localRepo.remoteAdd()
            .setName("origin")
            .setUri(URIish(remoteRepo.httpTransportUrl))
            .call()

        localRepo.commit()
            .setAuthor(
                githubClient.myself.login,
                githubClient.myself.email
            )
            .setCommitter(
                githubClient.myself.login,
                githubClient.myself.email
            )
            .setMessage("Initial commit")
            .setAllowEmpty(true)
            .call()

        localRepo.branchCreate()
            .setName(branchName)
            .setForce(true)
            .call()

        localRepo.checkout()
            .setName(branchName)
            .call()

        GithubScope(
            remoteRepo,
            localRepo,
            branchName,
            baseName,
            workDirFile,
            true
        )
    }

    logger.info("Applying operation")
    val result = scope.mutateOrRead()

    val localRepo = scope.localRepository
    val branchName = scope.branchName
    val baseName = scope.baseName

    logger.info("Adding all changed files")
    val changedFiles = scope.changedFiles
    if (changedFiles == null) {
        localRepo.add()
            .addFilepattern(".")
            .call();
        localRepo.add()
            .setUpdate(true)
            .addFilepattern(".")
            .call();
    } else {
        localRepo.add()
            .apply {
                changedFiles.forEach { addFilepattern(it) }
            }
            .call()
        localRepo.add()
            .setUpdate(true)
            .apply {
                changedFiles.forEach { addFilepattern(it) }
            }
            .call()
    }

    val status = localRepo.status()
        .call()

    if (!status.isClean()) {
        if (status.uncommittedChanges.size == 1) {
            logger.info("Commiting {}", status.uncommittedChanges.first())
        } else {
            logger.info("Commiting {} files", status.uncommittedChanges.size)
        }
        localRepo.commit()
            .setAuthor(
                githubClient.myself.login,
                githubClient.myself.email
            )
            .setCommitter(
                githubClient.myself.login,
                githubClient.myself.email
            )
            .setMessage(scope.commitMessage ?: buildCommitMessage(status, 5))
            .call()
    }

    logger.info("Pushing changes")
    localRepo.push()
        .setCredentialsProvider(credProvider)
        .setRemote("origin")
        .add(branchName)
        .call()

    val branchObj = scope.githubRepository.getBranch(branchName)
    val baseObj = scope.githubRepository.getBranch(baseName)

    val hasDifference = branchObj.shA1 != baseObj.shA1

    if (hasDifference) {
        val prMessage = scope.pullRequestMessage
        if (prMessage != null) {
            logger.info("Checking if opened pull request from $baseName to $branchName exists")
            val iterator = scope.githubRepository.queryPullRequests()
                .state(GHIssueState.OPEN)
                .head("$org:$branchName")
                .base(baseName)
                .list()
                .iterator()

            if (iterator.hasNext()) {
                logger.info("Pull request: {}", iterator.next().htmlUrl)
            } else {
                val pos = prMessage.indexOf("\n")
                val (title, body) = if (pos != -1) {
                    prMessage.substring(0, pos) to prMessage.substring(pos + 1)
                } else {
                    prMessage to ""
                }

                logger.info("Creating pull request: {}", title)
                val url = scope.githubRepository.createPullRequest(
                    title,
                    branchName,
                    baseName,
                    body
                ).htmlUrl
                logger.info("Pull request: {}", url)
            }
        }
    } else {
        if (branchName != baseName) {
            logger.info("No changes in branch $branchName relatively to $baseName as a result")
        }
    }

    val newName = when {
        scope.repoCreated && scope.newRepoDescription != null -> scope.newRepoDescription
        scope.repoDescription != null -> scope.repoDescription
        else -> null
    }

    newName?.let {
        logger.info("Changing repository description to '{}'", it)
        scope.githubRepository.description = it
    }

    return result
}

fun buildCommitMessage(status: Status, n: Int): String {
    val changes = status.uncommittedChanges

    if (changes.isEmpty()) {
        return "No changes"
    } else if (changes.size == 1) {
        return "Changed ${changes.first()}"
    } else if (changes.size <= n) {
        return "Changed:\n${changes.joinToString("\n")}"
    } else {
        return "Changed:\n${changes.take(n).joinToString("\n")}\n...and ${changes.size - n} files..."
    }

}

private class CredentialsFromGithub(private val builder: GitHubBuilder) : CredentialsProvider() {
    override fun isInteractive(): Boolean {
        return false
    }

    @Throws(UnsupportedCredentialItem::class)
    override fun get(
        uri: URIish,
        vararg items: CredentialItem?
    ): Boolean {
        for (item in items) {
            if (item == null) {
                continue
            }
            val password = builder.getField("password")
            val username = builder.getField("user")
            val token = builder.getField("oauthToken")

            val (user, pwd) = when {
                (password == null && token != null) -> token to ""
                username == null -> throw RuntimeException("missing username")
                (token == null && password != null) -> username to password
                else -> throw RuntimeException("bad credential combination")
            }

            when (item) {
                is CredentialItem.Username -> item.value = user
                is CredentialItem.Password -> item.value = pwd.toCharArray()
                is CredentialItem.StringType ->
                    if (item.promptText == "Password: ") {
                        item.value = pwd
                    }
                else -> throw UnsupportedCredentialItem(
                    uri, item::class.java.name + ":" + item.promptText
                ) //$NON-NLS-1$

            }
        }
        return !items.any { it == null }
    }

    override fun supports(vararg items: CredentialItem?) =
        items.none {
            it is CredentialItem.Username &&
                    it is CredentialItem.Password
        }

    private fun Any.getField(name: String): String? {
        return this::class.java
            .declaredFields
            .first { it.name == name }
            .apply {
                try {
                    isAccessible = true
                } catch (ex: Exception) {
                }
            }
            .get(this) as String?
    }

}

fun main() {
    github("oleksiyp", "spark", branch = "def5", base="abc") {
        changedFiles = listOf("pom.xml")

    }
}
