package net.ocheyedan.ply.script;

import net.ocheyedan.ply.Output;
import net.ocheyedan.ply.PlyUtil;
import net.ocheyedan.ply.SlowTaskThread;
import net.ocheyedan.ply.SystemExit;
import net.ocheyedan.ply.dep.*;
import net.ocheyedan.ply.graph.DirectedAcyclicGraph;
import net.ocheyedan.ply.graph.Vertex;
import net.ocheyedan.ply.props.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static net.ocheyedan.ply.props.PropFile.Prop;

/**
 * User: blangel
 * Date: 9/29/11
 * Time: 9:41 AM
 *
 * The default dependency manager for the ply build system.
 *
 * The property file used to configure this script is {@literal depmngr.properties} and so the context is {@literal depmngr}.
 * The following properties exist:
 * localRepo=string [[default=${PLY_HOME}/repo]] (this is the local repository where remote dependencies will
 *           be stored.  Note, multiple local-filesystem repositories may exist within {@literal repositories.properties}
 *           but only this, the {@literal localRepo}, will be used to store remote repositories' downloads.  The format
 *           is [type:]repoUri, see below for description of this format).
 *
 * Dependency information is stored in a file called {@literal dependencies[.scope].properties} and so the context is
 * {@literal dependencies[.scope]}.  The format of each property within the file is:
 * namespace:name=version:artifactName
 * where namespace provides a unique context for name.  It is analogous to {@literal groupId} in {@literal Maven} or
 * the {@literal category} portion of a base atom in {@literal portage}.
 * The name is the name of the project.  It is analogous to {@literal artifactId} in {@literal Maven} or the
 * {@literal packagename} portion of a base atom in {@literal portage}.
 * The version is the version of the project.  It is analogous to {@literal version} in {@literal Maven} or the
 * atom version in {@literal portage}.
 * TODO (perhaps not) - The version portion may be null. Leaving/setting the version to null for a dependency is
 * TODO - interpreted as meaning find and use the latest version of the dependency.  The latest version is that saved
 * TODO = into the repository last.
 * The artifactName is the file name of the dependency.  It may be null and if so is assumed to be {@literal name-version.jar}
 * where {@literal name} and {@literal version} correspond to the definition of the dependency.
 *
 * Dependencies are resolved and downloaded from repositories.  The list of repositories are stored in a file
 * called {@literal repositories.properties} (so have context {@literal repositories}).  The format of each property
 * within the file is:
 * repositoryURI=type
 * where the {@literal repositoryURI} is a {@link java.net.URI} to the repository and {@literal type} is
 * the type of the repository.  Type is currently either {@literal ply} (the default so null resolves to ply) or {@literal maven}.
 * Dependencies are then resolved by appending the namespace/name/version/artifactName to the {@literal repositoryURI}.
 * For instance, if the {@literal repositoryURI} were {@literal http://repo1.maven.org/maven2} and the dependency were
 * {@literal org.apache.commons:commons-io=1.3.2:} then the dependency would be resolved by assuming the default
 * artifactName (which would be {@literal commons-io-1.3.2.jar}) and creating a link to the artifact.  If the type
 * of the repository were null or {@literal ply} the artifact would be:
 * {@literal http://repo1.maven.org/maven2/org.apache.commons/1.3.2/commons-io-1.3.2.jar}.
 * If the type were {@literal maven} the artifact would be:
 * {@literal http://repo1.maven.org/maven2/org/apache/commons/1.3.2/commons-io-1.3.2.jar}.
 * The difference between the two is that with the {@literal maven} type the dependency's {@literal namespace}'s periods
 * are resolved to forward slashes as is convention in the {@literal Maven} build system.
 *
 * This script, run without arguments will resolve all the dependencies listed in
 * {@literal dependencies[.scope].properties} and store the values in file {@literal resolved-deps[.scope].properties}
 * under the {@literal project.build.dir}.  This file will contain local file references (local to the {@literal localRepo})
 * for dependencies and transitive dependencies so that compilation and packaging may succeed.
 *
 * The dependency script's usage is:
 * <pre>dep [--usage] [add|remove|list|tree|add-repo|remove-repo]</pre>
 * where {@literal --usage} prints the usage information.
 * The {@literal add} command takes an atom and adds it as a dependency for the supplied scope, resolving it eagerly
 * from the known repos and failing if it cannot be resolved.
 * The {@literal remove} command takes an atom and removes it from the dependencies scope, if it exists.
 * The {@literal list} command lists all direct dependencies for the scope (transitive dependencies are not listed).
 * The {@literal tree} command lists all dependencies for the scope in a tree format (including transitive).
 * The {@literal add-repo} command takes a repository and adds it to the repositories.
 * The {@literal remove-repo} command removes the repository.
 *
 * If nothing is passed to the script then dependency resolution is done for all dependencies against the known
 * repositories.
 */
public class DependencyManager {

    private static final String TRANSIENT_PRINT = " ^black^[transient]^r^";

    public static void main(String[] args) {
        if ((args == null) || (args.length > 0 && "--usage".equals(args[0]))) {
            usage();
            return;
        }
        Scope scope = Scope.named(Props.get("scope", Context.named("ply")).value());
        Context projectContext = Context.named("project");
        if ((args.length > 1) && "add".equals(args[0])) {
            addDependency(args[1], scope);
        } else if ((args.length > 1) && "remove".equals(args[0])) {
            removeDependency(args[1], scope);
        } else if ((args.length == 1) && "list".equals(args[0])) {
            PropFile dependencies = getDependencies(scope);
            int size = dependencies.size();
            if (size > 0) {
                Output.print("Project ^b^%s^r^ has ^b^%d^r^ %sdependenc%s: ", Props.get("name", projectContext).value(), size,
                        scope.getPrettyPrint(), (size == 1 ? "y" : "ies"));
                for (PropFile.Prop dep : dependencies.props()) {
                    String value = dep.value();
                    boolean transientDep = DependencyAtom.isTransient(value);
                    if (transientDep) {
                        value = DependencyAtom.stripTransient(value);
                    }
                    Output.print("\t%s:%s%s", dep.name, value, (transientDep ? TRANSIENT_PRINT : ""));
                }
            } else {
                Output.print("Project ^b^%s^r^ has no %sdependencies.", Props.get("name", projectContext).value(), scope.getPrettyPrint());
            }
        } else if ((args.length == 1) && "tree".equals(args[0])) {
            List<DependencyAtom> dependencies = Deps.parse(getDependencies(scope));
            if (dependencies.isEmpty()) {
                Output.print("Project ^b^%s^r^ has no %sdependencies.", Props.get("name", projectContext).value(), scope.getPrettyPrint());
            } else {
                DirectedAcyclicGraph<Dep> depGraph = Deps.getDependencyGraph(dependencies, createRepositoryList(null, null));
                int size = dependencies.size();
                int graphSize = depGraph.getRootVertices().size();
                if (graphSize > size) {
                    throw new AssertionError("Dependency graph's root-vertices should not be greater than the specified dependencies.");
                }
                String sizeExplanation = (size != graphSize) ?
                        String.format(" [ actually %d; %d of which are pulled in transitively ]", size, (size - graphSize)) : "";
                Output.print("Project ^b^%s^r^ has ^b^%d^r^ direct %sdependenc%s%s: ", Props.get("name", projectContext).value(), graphSize,
                        scope.getPrettyPrint(), (size == 1 ? "y" : "ies"), sizeExplanation);
                printDependencyGraph(depGraph.getRootVertices(), String.format("%s ", PlyUtil.isUnicodeSupported() ? "\u26AC" : "+"), new HashSet<Vertex<Dep>>());
            }
        } else if ((args.length > 1) && "add-repo".equals(args[0])) {
            addRepository(args[1], scope);
        } else if ((args.length > 1) && "remove-repo".equals(args[0])) {
            removeRepository(args[1], scope);
        } else if ((args.length > 1) && "resolve-classifiers".equals(args[0])) {
            String[] classifiers = args[1].split(",");
            for (String classifier : classifiers) {
                PropFile dependencies = getDependencies(scope);
                resolveDependencies(dependencies, classifier, false);
            }
        } else if (args.length == 0) {
            PropFile dependencies = getDependencies(scope);
            int size = dependencies.size();
            if (size > 0) {
                Output.print("Resolving ^b^%d^r^ %sdependenc%s for ^b^%s^r^.", size, scope.getPrettyPrint(), (size == 1 ? "y" : "ies"),
                        Props.get("name", projectContext).value());
                PropFile dependencyFiles = resolveDependencies(dependencies, null, true);
                storeResolvedDependenciesFile(dependencyFiles, scope);
            }
        } else {
            usage();
        }
    }

    private static void addDependency(String dependency, Scope scope) {
        AtomicReference<String> error = new AtomicReference<String>(null);
        DependencyAtom atom = DependencyAtom.parse(dependency, error);
        if (atom == null) {
            Output.print("^error^ Dependency ^b^%s^r^ ^b^%s^r^ (format namespace:name:version[:artifactName]).",
                    dependency, error.get());
            throw new SystemExit(1);
        }
        PropFile dependencies = loadDependenciesFile(scope);
        if (dependencies.contains(atom.getPropertyName())) {
            Output.print("^info^ overriding %sdependency %s; was %s now is %s.", scope.getPrettyPrint(), atom.getPropertyName(),
                    dependencies.get(atom.getPropertyName()).value(), atom.getPropertyValue());
            dependencies.remove(atom.getPropertyName());
        }
        dependencies.add(atom.getPropertyName(), atom.getPropertyValue());
        final List<DependencyAtom> dependencyAtoms = Deps.parse(dependencies);
        final RepositoryRegistry repositoryRegistry = createRepositoryList(Deps.getProjectDep(), dependencyAtoms);
        DirectedAcyclicGraph<Dep> resolvedDeps = invokeWithSlowResolutionThread(new Callable<DirectedAcyclicGraph<Dep>>() {
            @Override public DirectedAcyclicGraph<Dep> call() throws Exception {
                return Deps.getDependencyGraph(dependencyAtoms, repositoryRegistry);
            }
        }, String.format("^b^Hang tight,^r^ %s has a lot of dependencies. ^b^Ply^r^'s downloading them...", dependency));
        if (resolvedDeps != null) { // getDependencyGraph returns => ok
            storeDependenciesFile(dependencies, scope);
        }
        Output.print("Added dependency %s%s", atom.toString(), !Scope.Default.equals(scope) ?
                String.format(" (in scope %s)", scope.getPrettyPrint()) : "");
    }

    private static void removeDependency(String dependency, Scope scope) {
        DependencyAtom atom = DependencyAtom.parse(dependency, null);
        if (atom == null) {
            // allow non-version specification.
            String[] split = dependency.split(":");
            if (split.length < 2) {
                Output.print("^error^ Dependency ^b^%s^r^ ^b^%s^r^ (format namespace:name[:version:artifactName]).",
                        dependency, (split.length == 1 ? "name" : "namespace and name"));
                System.exit(1);
            }
            atom = new DependencyAtom(split[0], split[1], null);
        }
        if (Props.get(atom.getPropertyName(), Context.named("dependencies")).value().isEmpty()) {
            Output.print("^warn^ Could not find %sdependency; given %s:%s", scope.getPrettyPrint(), atom.getPropertyName(),
                    atom.getPropertyValue());
        } else {
            PropFile dependencies = loadDependenciesFile(scope);
            dependencies.remove(atom.getPropertyName());
            storeDependenciesFile(dependencies, scope);
            Output.print("Removed dependency %s%s", atom.toString(), !Scope.Default.equals(scope) ?
                String.format(" (in scope %s)", scope.getPrettyPrint()) : "");
        }
    }

    private static void addRepository(String repository, Scope scope) {
        RepositoryAtom atom = RepositoryAtom.parse(repository);
        if (atom == null) {
            Output.print("^error^ Repository %s not of format [type:]repoUri.", repository);
            System.exit(1);
        }
        try {
            atom.repositoryUri.toURL();
        } catch (Exception e) {
            Output.print("^error^ Given value ^b^%s^r^ is not a valid URL and so is not a repository.",
                    atom.getPropertyName());
            System.exit(1);
        }
        PropFile repositories = loadRepositoriesFile(scope);
        if (repositories.contains(atom.getPropertyName())) {
            Output.print("^info^ overriding repository %s; was %s now is %s.", atom.getPropertyName(),
                    repositories.get(atom.getPropertyName()).value(), atom.getPropertyValue());
            repositories.remove(atom.getPropertyName());
        }
        repositories.add(atom.getPropertyName(), atom.getPropertyValue());
        storeRepositoriesFile(repositories, scope);
        Output.print("Added repository %s%s", atom.toString(), !Scope.Default.equals(scope) ?
                String.format(" (in scope %s)", scope.getPrettyPrint()) : "");
    }

    private static void removeRepository(String repository, Scope scope) {
        RepositoryAtom atom = RepositoryAtom.parse(repository);
        if (atom == null) {
            Output.print("^error^ Repository %s not of format [type:]repoUri.", repository);
            System.exit(1);
        }

        if (Props.get(atom.getPropertyName(), Context.named("repositories")).value().isEmpty()) {
            Output.print("^warn^ Repository not found; given %s:%s", atom.getPropertyValue(),
                    atom.getPropertyName());
        } else {
            PropFile repositories = loadRepositoriesFile(scope);
            repositories.remove(atom.getPropertyName());
            storeRepositoriesFile(repositories, scope);
            Output.print("Removed repository %s%s", atom.toString(), !Scope.Default.equals(scope) ?
                String.format(" (in scope %s)", scope.getPrettyPrint()) : "");
        }
    }

    private static RepositoryRegistry createRepositoryList(DependencyAtom dependencyAtom, List<DependencyAtom> dependencyAtoms) {
        RepositoryAtom localRepo = RepositoryAtom.parse(Props.get("localRepo", Context.named("depmngr")).value());
        if (localRepo == null) {
            Output.print("^error^ No ^b^localRepo^r^ property defined (^b^ply set localRepo=xxxx in depmngr^r^).");
            System.exit(1);
        }
        List<RepositoryAtom> repositoryAtoms = new ArrayList<RepositoryAtom>();
        PropFileChain repositories = Props.get(Context.named("repositories"));
        if (repositories != null) {
            for (Prop repoProp : repositories.props()) {
                String repoUri = repoProp.name;
                if (localRepo.getPropertyName().equals(repoUri)) {
                    continue;
                }
                String repoType = repoProp.value();
                String repoAtom = repoType + ":" + repoUri;
                RepositoryAtom repo = RepositoryAtom.parse(repoAtom);
                if (repo == null) {
                    Output.print("^warn^ Invalid repository declared %s, ignoring.", repoAtom);
                } else {
                    repositoryAtoms.add(repo);
                }
            }
        }
        Collections.sort(repositoryAtoms, RepositoryAtom.LOCAL_COMPARATOR);
        Map<DependencyAtom, List<DependencyAtom>> synthetic = null;
        if (dependencyAtom != null) {
            synthetic = new HashMap<DependencyAtom, List<DependencyAtom>>(1);
            synthetic.put(dependencyAtom, dependencyAtoms);
        }
        return new RepositoryRegistry(localRepo, repositoryAtoms, synthetic);
    }

    private static PropFile resolveDependencies(final PropFile dependencies, final String classifier,
                                                  final boolean failMissingDependency) {
        return invokeWithSlowResolutionThread(new Callable<PropFile>() {
            @Override public PropFile call() throws Exception {
                DependencyAtom self = Deps.getProjectDep();
                List<DependencyAtom> dependencyAtoms = Deps.parse(dependencies);
                if (classifier != null) {
                    List<DependencyAtom> dependencyAtomWithClassifiers = new ArrayList<DependencyAtom>(dependencyAtoms.size());
                    for (DependencyAtom dependencyAtom : dependencyAtoms) {
                        dependencyAtomWithClassifiers.add(dependencyAtom.withClassifier(classifier));
                    }
                    dependencyAtoms = dependencyAtomWithClassifiers;
                }
                RepositoryRegistry repositoryRegistry = createRepositoryList(self, dependencyAtoms);
                DirectedAcyclicGraph<Dep> dependencyGraph = Deps.getDependencyGraph(dependencyAtoms, repositoryRegistry, failMissingDependency);
                return Deps.convertToResolvedPropertiesFile(dependencyGraph);
            }
        }, "^b^Hang tight,^r^ your project needs a lot of dependencies. ^b^Ply^r^'s downloading them...");
    }

    private static <T> T invokeWithSlowResolutionThread(Callable<T> callable, String message) {
        // if the project hasn't already resolved these dependencies locally and is not running with 'info' logging
        // it appears that ply has hung if downloading lots of dependencies...print out a warning if not running
        // in 'info' logging and dependency resolution takes longer than 2 seconds.
        try {
            return SlowTaskThread.<T>after(2000).warn(message).onlyIfNotLoggingInfo().whenDoing(callable).start();
        } catch (Exception e) {
            Output.print(e);
            throw new AssertionError(e);
        }
    }

    private static PropFile getDependencies(Scope scope) {
        // note, for non-default scoped invocations this is redundant as we add a dependency to the
        // default scope itself (which via transitive deps will depend upon all the inherited deps anyway).
        // TODO - is this wrong? essentially saying transitive deps are first level deps for non-default scopes
        PropFileChain scopedDependencies = Props.get(Context.named("dependencies"));
        PropFile dependencies = new PropFile(Context.named("dependencies"), scope, PropFile.Loc.Local);
        if (!scope.name.isEmpty()) {
            // add the project itself as this is not the default scope
            DependencyAtom self = DependencyAtom.parse(Props.get("nonscoped.artifact.name", Context.named("project")).value(), null);
            if (self == null) {
                throw new AssertionError("Could not determine the project information.");
            }
            dependencies.add(self.namespace + ":" + self.name, self.version + ":" + self.getArtifactName());
        }
        for (Prop dependency : scopedDependencies.props()) {
            dependencies.add(dependency.name, dependency.value());
        }
        return dependencies;
    }

    private static PropFile loadDependenciesFile(Scope scope) {
        String localDir = Props.get("project.dir", Context.named("ply")).value();
        String loadPath = localDir + (localDir.endsWith(File.separator) ? "" : File.separator) + "config" +
                File.separator + "dependencies" + scope.getFileSuffix() + ".properties";
        return PropFiles.load(loadPath, true, false);
    }

    private static PropFile loadRepositoriesFile(Scope scope) {
        String localDir = Props.get("project.dir", Context.named("ply")).value();
        String loadPath = localDir + (localDir.endsWith(File.separator) ? "" : File.separator) + "config" +
                File.separator + "repositories" + scope.getFileSuffix() + ".properties";
        return PropFiles.load(loadPath, true, false);
    }

    private static void storeDependenciesFile(PropFile dependencies, Scope scope) {
        String localDir = Props.get("project.dir", Context.named("ply")).value();
        String storePath = localDir + (localDir.endsWith(File.separator) ? "" : File.separator) + "config" +
                File.separator + "dependencies" + scope.getFileSuffix() + ".properties";
        if (!PropFiles.store(dependencies, storePath, true)) {
            System.exit(1);
        }
    }

    private static void storeRepositoriesFile(PropFile repositories, Scope scope) {
        String localDir = Props.get("project.dir", Context.named("ply")).value();
        String storePath = localDir + (localDir.endsWith(File.separator) ? "" : File.separator) + "config" +
                File.separator + "repositories" + scope.getFileSuffix() + ".properties";
        if (!PropFiles.store(repositories, storePath, true)) {
            System.exit(1);
        }
    }

    private static void storeResolvedDependenciesFile(PropFile resolvedDependencies, Scope scope) {
        String buildDirPath = Props.get("build.dir", Context.named("project")).value();
        String storePath = buildDirPath + (buildDirPath.endsWith(File.separator) ? "" : File.separator)
                + "resolved-deps" + scope.getFileSuffix() + ".properties";
        if (!PropFiles.store(resolvedDependencies, storePath, true)) {
            System.exit(1);
        }
    }

    private static void printDependencyGraph(List<Vertex<Dep>> vertices, String indent, Set<Vertex<Dep>> encountered) {
        if ((vertices == null) || vertices.isEmpty()) {
            return;
        }
        for (Vertex<Dep> vertex : vertices) {
            boolean enc = encountered.contains(vertex);
            if (!enc) {
                encountered.add(vertex);
            }
            Dep dep = vertex.getValue();
            String name = dep.dependencyAtom.getPropertyName();
            String version = dep.dependencyAtom.getPropertyValueWithoutTransient();
            Output.print("%s^b^%s:%s^r^%s%s", indent, name, version, (dep.dependencyAtom.transientDep ? TRANSIENT_PRINT : ""),
                    (enc ? " (already printed)" : ""));
            if (!enc && !dep.dependencyAtom.transientDep) {
                printDependencyGraph(vertex.getChildren(), String.format("  %s %s", PlyUtil.isUnicodeSupported() ? "\u2937" : "\\", indent), encountered);
            }
        }
    }

    private static void usage() {
        Output.print("dep [--usage] <^b^command^r^>");
        Output.print("  where ^b^command^r^ is either:");
        Output.print("    ^b^add <dep-atom>^r^ : adds dep-atom to the list of dependencies (within scope) (or replacing the version if it already exists).");
        Output.print("    ^b^remove <dep-atom>^r^ : removes dep-atom from the list of dependencies (within scope).");
        Output.print("    ^b^list^r^ : list all direct dependencies (within scope excluding transitive dependencies).");
        Output.print("    ^b^tree^r^ : print all dependencies in a tree view (within scope including transitive dependencies).");
        Output.print("    ^b^add-repo <rep-atom>^r^ : adds rep-atom to the list of repositories.");
        Output.print("    ^b^remove-repo <rep-atom>^r^ : removes rep-atom from the list of repositories.");
        Output.print("    ^b^resolve-classifiers <classifiers>^r^ : resolves dependencies with each of the (comma delimited) classifiers.");
        Output.print("  ^b^dep-atom^r^ is namespace:name:version[:artifactName] (artifactName is optional and defaults to name-version.jar).");
        Output.print("  ^b^rep-atom^r^ is [type:]repoURI (type is optional and defaults to ply, must be either ply or maven).");
        Output.print("  if no command is passed then dependency resolution is done for all dependencies against the known repositories.");
        Output.print("  Dependencies can be grouped by ^b^scope^r^ (i.e. test).  The default scope is null.");
    }

}
