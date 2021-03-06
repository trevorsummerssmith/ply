package net.ocheyedan.ply.script;

import net.ocheyedan.ply.BitUtil;
import net.ocheyedan.ply.FileUtil;
import net.ocheyedan.ply.Output;
import net.ocheyedan.ply.props.*;

import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User: blangel
 * Date: 9/12/11
 * Time: 9:20 PM
 *
 * This scope property (ply.scope) is used as a suffix to the file
 * names created by this script.  If the scope is the default then there will be no suffix.  This file name suffix is
 * referred to as '${suffix}' below.
 *
 * Determines which files within {@literal project[.scope].src.dir} have changed since last invocation.
 * The information used to determine if a file has changed is saved in the {@literal project.build.dir} in a file named
 * {@literal changed-meta[.${suffix}].properties}.  The list of files which have changed since last invocation is stored
 * in a file named {@literal changed[.${suffix}].properties} in directory {@literal project[.scope].build.dir}.
 * The information used to determine change is stored relative to {@literal project[.scope].build.dir} to allow for cleans to
 * force a full-recompilation.  The format of the {@literal changed-meta[.${suffix}].properties} file is:
 * file-path=timestamp,sha1-hash
 * and the format of the {@literal changed[.${suffix}].properties} is simply a listing of file paths which have changed.
 *
 * By default only the files' timestamps are consulted.  Call this script with the {@link --compute-hash} to
 * perform a {@literal SHA1} hash of the file to assist in determining whether the file has been updated.  Clearly
 * this is a more expensive operation but may save time if dependent actions are time-intensive and having
 * the hash would reduce the amount of processing by dependent actions.
 */
public class FileChangeDetector {

    public static void main(String[] args) {
        boolean computeSha1Hash = false;
        if ((args != null) && (args.length > 0) && "--compute-hash".equals(args[0])) {
            computeSha1Hash = true;
        }
        Scope scope = Scope.named(Props.get("scope", Context.named("ply")).value());
        String srcDirPath = Props.get("src.dir", Context.named("project")).value();
        String buildDirPath = Props.get("build.dir", Context.named("project")).value();
        File lastSrcChanged = FileUtil.fromParts(buildDirPath, "changed-meta" + scope.getFileSuffix() + ".properties");
        File changedPropertiesFile = FileUtil.fromParts(buildDirPath, "changed" + scope.getFileSuffix() + ".properties");
        File srcDir = new File(srcDirPath);
        PropFile existing = PropFiles.load(lastSrcChanged.getPath(), true, false);
        try {
            changedPropertiesFile.createNewFile();
        } catch (IOException ioe) {
            Output.print(ioe);
        }
        computeFilesChanged(lastSrcChanged, changedPropertiesFile, srcDir, existing, scope, computeSha1Hash);
    }

    private static void computeFilesChanged(File lastSrcChanged, File changedPropertiesFile, File srcDir,
                                            PropFile existing, Scope scope, boolean computeSha1Hash) {
        PropFile changedList = new PropFile(Context.named("changed"), PropFile.Loc.Local);
        PropFile properties = new PropFile(Context.named("changed-meta"), PropFile.Loc.Local);
        collectAllFileChanges(srcDir, changedList, properties, existing, scope, computeSha1Hash);
        PropFiles.store(changedList, changedPropertiesFile.getPath());
        PropFiles.store(properties, lastSrcChanged.getPath());
    }

    private static void collectAllFileChanges(File from, PropFile changedList, PropFile into, PropFile existing,
                                              Scope scope, boolean computeSha1Hash) {
        File[] subfiles = from.listFiles();
        if (subfiles == null) {
            return;
        }
        for (File file : subfiles) {
            if (file.isDirectory()) {
                collectAllFileChanges(file, changedList, into, existing, scope, computeSha1Hash);
            } else {
                try {
                    AtomicReference<String> sha1HashRef = new AtomicReference<String>();
                    String path = file.getCanonicalPath();
                    if (hasChanged(file, existing, sha1HashRef, scope, computeSha1Hash) && file.exists()) {
                        String timeFileLastChanged = String.valueOf(file.lastModified());
                        String sha1Hash =
                                (computeSha1Hash
                                        ? (sha1HashRef.get() == null ? computeSha1Hash(file) : sha1HashRef.get())
                                        : "not-computed");
                        into.add(path, timeFileLastChanged + "," + sha1Hash);
                        changedList.add(path, "");
                    } else if (file.exists()) {
                        into.add(path, existing.get(path).value());
                    }
                } catch (IOException ioe) {
                    Output.print(ioe);
                }
            }
        }
    }

    private static boolean hasChanged(File file, PropFile existing, AtomicReference<String> computedSha1, Scope scope,
                                      boolean computeSha1Hash) {
        try {
            String propertyValue;
            if ((propertyValue = existing.get(file.getCanonicalPath()).value()).isEmpty()) {
                return true;
            }
            String[] split = propertyValue.split("\\,");
            if (split.length != 2) {
                Output.print("^warn^ corrupted changed-meta%s.properties file, recomputing.", scope.getFileSuffix());
                return true;
            }
            long timestamp = Long.valueOf(split[0]);
            if (file.lastModified() == timestamp) {
                return false;
            }
            if (computeSha1Hash) {
                String oldHashAsHex = split[1];
                String asHex = computeSha1Hash(file);
                computedSha1.set(asHex);
                return !asHex.equals(oldHashAsHex);
            } else {
                computedSha1.set("not-computed");
                return true;
            }
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        } catch (NumberFormatException nfe) {
            Output.print("^warn^ corrupted changed-meta%s.properties file, recomputing.", scope.getFileSuffix());
            return true;
        }
    }

    private static String computeSha1Hash(File file) {
        InputStream fileInputStream = null;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA1");
            fileInputStream = new BufferedInputStream(new FileInputStream(file));
            DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, hash);
            byte[] buffer = new byte[8192];
            while (digestInputStream.read(buffer, 0, 8192) != -1) { }
            byte[] sha1 = hash.digest();
            return BitUtil.toHexString(sha1);
        } catch (NoSuchAlgorithmException nsae) {
            throw new AssertionError(nsae);
        } catch (FileNotFoundException fnfe) {
            throw new AssertionError(fnfe);
        } catch (IOException ioe) {
            Output.print(ioe);
        } finally {
            try {
                if (fileInputStream != null) {
                    fileInputStream.close();
                }
            } catch (IOException ioe) {
                throw new AssertionError(ioe);
            }
        }
        return ""; // error!
    }

}