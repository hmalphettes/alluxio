/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs.swift;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.underfs.ObjectUnderFileSystem;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.DeleteOptions;
import alluxio.underfs.options.MkdirsOptions;
import alluxio.underfs.swift.http.SwiftDirectClient;
import alluxio.util.CommonUtils;
import alluxio.util.io.PathUtils;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.javaswift.joss.client.factory.AccountConfig;
import org.javaswift.joss.client.factory.AccountFactory;
import org.javaswift.joss.client.factory.AuthenticationMethod;
import org.javaswift.joss.exception.CommandException;
import org.javaswift.joss.model.Access;
import org.javaswift.joss.model.Account;
import org.javaswift.joss.model.Container;
import org.javaswift.joss.model.DirectoryOrObject;
import org.javaswift.joss.model.PaginationMap;
import org.javaswift.joss.model.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;

/**
 * OpenStack Swift {@link UnderFileSystem} implementation based on the JOSS library.
 * The mkdir operation creates a zero-byte object.
 * A suffix {@link SwiftUnderFileSystem#PATH_SEPARATOR} in the object name denotes a folder.
 * JOSS directory listing API requires that the suffix be a single character.
 */
@ThreadSafe
public class SwiftUnderFileSystem extends ObjectUnderFileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Regexp for Swift container ACL separator, including optional whitespaces. */
  private static final String ACL_SEPARATOR_REGEXP = "\\s*,\\s*";

  /** Suffix for an empty file to flag it as a directory. */
  private static final String FOLDER_SUFFIX = PATH_SEPARATOR;

  /** Number of retries in case of Swift internal errors. */
  private static final int NUM_RETRIES = 3;

  /** Swift account. */
  private final Account mAccount;

  /** Container name of user's configured Alluxio container. */
  private final String mContainerName;

  /** JOSS access object. */
  private final Access mAccess;

  /** Determine whether to run JOSS in simulation mode. */
  private boolean mSimulationMode;

  /** The name of the account owner. */
  private String mAccountOwner;

  /** The permission mode that the account owner has to the container. */
  private short mAccountMode;

  /**
   * Constructs a new Swift {@link UnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   */
  public SwiftUnderFileSystem(AlluxioURI uri) {
    super(uri);
    String containerName = getContainerName(uri);
    LOG.debug("Constructor init: {}", containerName);
    AccountConfig config = new AccountConfig();

    // Whether to run against a simulated Swift backend
    mSimulationMode = false;
    if (Configuration.containsKey(PropertyKey.SWIFT_SIMULATION)) {
      mSimulationMode = Configuration.getBoolean(PropertyKey.SWIFT_SIMULATION);
    }

    if (mSimulationMode) {
      // We do not need access credentials in simulation mode
      config.setMock(true);
      config.setMockAllowEveryone(true);
    } else {
      if (Configuration.containsKey(PropertyKey.SWIFT_API_KEY)) {
        config.setPassword(Configuration.get(PropertyKey.SWIFT_API_KEY));
      } else if (Configuration.containsKey(PropertyKey.SWIFT_PASSWORD_KEY)) {
        config.setPassword(Configuration.get(PropertyKey.SWIFT_PASSWORD_KEY));
      }
      config.setAuthUrl(Configuration.get(PropertyKey.SWIFT_AUTH_URL_KEY));
      String authMethod = Configuration.get(PropertyKey.SWIFT_AUTH_METHOD_KEY);
      if (authMethod != null) {
        config.setUsername(Configuration.get(PropertyKey.SWIFT_USER_KEY));
        config.setTenantName(Configuration.get(PropertyKey.SWIFT_TENANT_KEY));
        switch (authMethod) {
          case Constants.SWIFT_AUTH_KEYSTONE:
            config.setAuthenticationMethod(AuthenticationMethod.KEYSTONE);
            if (Configuration.containsKey(PropertyKey.SWIFT_REGION_KEY)) {
              config.setPreferredRegion(Configuration.get(PropertyKey.SWIFT_REGION_KEY));
            }
            break;
          case Constants.SWIFT_AUTH_SWIFTAUTH:
            // swiftauth authenticates directly against swift
            // note: this method is supported in swift object storage api v1
            config.setAuthenticationMethod(AuthenticationMethod.BASIC);
            // swiftauth requires authentication header to be of the form tenant:user.
            // JOSS however generates header of the form user:tenant.
            // To resolve this, we switch user with tenant
            config.setTenantName(Configuration.get(PropertyKey.SWIFT_USER_KEY));
            config.setUsername(Configuration.get(PropertyKey.SWIFT_TENANT_KEY));
            break;
          default:
            config.setAuthenticationMethod(AuthenticationMethod.TEMPAUTH);
            // tempauth requires authentication header to be of the form tenant:user.
            // JOSS however generates header of the form user:tenant.
            // To resolve this, we switch user with tenant
            config.setTenantName(Configuration.get(PropertyKey.SWIFT_USER_KEY));
            config.setUsername(Configuration.get(PropertyKey.SWIFT_TENANT_KEY));
        }
      }
    }

    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationConfig.Feature.WRAP_ROOT_VALUE, true);
    mContainerName = containerName;
    mAccount = new AccountFactory(config).createAccount();
    // Do not allow container cache to avoid stale directory listings
    mAccount.setAllowContainerCaching(false);
    mAccess = mAccount.authenticate();
    Container container = mAccount.getContainer(containerName);
    if (!container.exists()) {
      container.create();
    }

    // Assume the Swift user name has 1-1 mapping to Alluxio username.
    mAccountOwner = Configuration.get(PropertyKey.SWIFT_USER_KEY);
    short mode = (short) 0;
    List<String> readAcl = Arrays.asList(
        container.getContainerReadPermission().split(ACL_SEPARATOR_REGEXP));
    // If there is any container ACL for the Swift user, translates it to Alluxio permission.
    if (readAcl.contains(mAccountOwner) || readAcl.contains("*") || readAcl.contains(".r:*")) {
      mode |= (short) 0500;
    }
    List<String> writeAcl = Arrays.asList(
        container.getcontainerWritePermission().split(ACL_SEPARATOR_REGEXP));
    if (writeAcl.contains(mAccountOwner) || writeAcl.contains("*") || writeAcl.contains(".w:*")) {
      mode |= (short) 0200;
    }
    // If there is no container ACL but the user can still access the container, the only
    // possibility is that the user has the admin role. In this case, the user should have 0700
    // mode to the Swift container.
    if (mode == 0 && mAccess.getToken() != null) {
      mode = (short) 0700;
    }
    mAccountMode = mode;
  }

  @Override
  public String getUnderFSType() {
    return "swift";
  }

  /**
   * Creates a simulated or actual OutputStream for object uploads.
   * @throws IOException if failed to create path
   * @return new OutputStream
   */
  @Override
  protected OutputStream createOutputStream(String path) throws IOException {
    if (mSimulationMode) {
      return new SwiftMockOutputStream(mAccount, mContainerName,
          stripPrefixIfPresent(path));
    }

    return SwiftDirectClient.put(mAccess,
        CommonUtils.stripPrefixIfPresent(path, Constants.HEADER_SWIFT));
  }

  @Override
  public boolean deleteDirectory(String path, DeleteOptions options) throws IOException {
    // TODO(adit): use bulk delete API
    LOG.debug("Delete directory {}, recursive {}", path, options.isRecursive());
    final String strippedPath = stripPrefixIfPresent(path);
    Container container = mAccount.getContainer(mContainerName);
    if (!options.isRecursive()) {
      String[] children = list(path);
      if (children == null) {
        LOG.error("Unable to delete {} because list returns null", path);
        return false;
      }
      if (children.length != 0) {
        LOG.error("Unable to delete {} because it is a non empty directory. Specify "
                + "recursive as true in order to delete non empty directories.", path);
        return false;
      }
    } else {
      // Delete children
      PaginationMap paginationMap = container.getPaginationMap(
          PathUtils.normalizePath(strippedPath, PATH_SEPARATOR), LISTING_LENGTH);
      for (int page = 0; page < paginationMap.getNumberOfPages(); page++) {
        for (StoredObject childObject : container.list(paginationMap, page)) {
          if (childObject.getName().equals(addFolderSuffixIfNotPresent(strippedPath))) {
            // As PATH_SEPARATOR=FOLDER_SUFFIX, the folder itself might be fetched
            continue;
          }
          deleteObject(childObject);
        }
      }
    }
    // Delete the directory itself
    String strippedFolderPath = addFolderSuffixIfNotPresent(strippedPath);
    return deleteObject(container.getObject(strippedFolderPath));
  }

  @Override
  public boolean deleteFile(String path) throws IOException {
    LOG.debug("Delete file {}", path);
    final String strippedPath = stripPrefixIfPresent(path);
    Container container = mAccount.getContainer(mContainerName);
    return deleteObject(container.getObject(strippedPath));
  }

  @Override
  public long getFileSize(String path) throws IOException {
    return getObject(path).getContentLength();
  }

  @Override
  public long getModificationTimeMs(String path) throws IOException {
    LOG.debug("Get modification time for {}", path);
    return getObject(path).getLastModifiedAsDate().getTime();
  }

  @Override
  public boolean isDirectory(String path) throws IOException {
    // Root is always a folder
    if (isRoot(path)) {
      return true;
    }

    final String pathAsFolder = addFolderSuffixIfNotPresent(path);
    return doesObjectExist(pathAsFolder);
  }

  @Override
  public boolean isFile(String path) throws IOException {
    String pathAsFile = stripFolderSuffixIfPresent(path);
    return doesObjectExist(pathAsFile);
  }

  @Override
  public String[] listRecursive(String path) throws IOException {
    LOG.debug("List {} recursively", path);
    return listHelper(path, true);
  }

  @Override
  public String[] list(String path) throws IOException {
    LOG.debug("List {}", path);
    return listHelper(path, false);
  }

  @Override
  public boolean mkdirs(String path, boolean createParent) throws IOException {
    return mkdirs(path, MkdirsOptions.defaults().setCreateParent(createParent));
  }

  @Override
  public boolean mkdirs(String path, MkdirsOptions options) throws IOException {
    LOG.debug("Make directory {}", path);
    if (path == null) {
      LOG.error("Attempting to create directory with a null path");
      return false;
    }
    if (isDirectory(path)) {
      return true;
    }
    if (isFile(path)) {
      LOG.error("Cannot create directory {} because it is already a file.", path);
      return false;
    }

    if (!parentExists(path)) {
      if (!options.getCreateParent()) {
        LOG.error("Cannot create directory {} because parent does not exist", path);
        return false;
      }
      final String parentPath = getParentKey(path);
      // TODO(adit): See how we can do this with better performance
      // Recursively make the parent folders
      if (!mkdirs(parentPath, true)) {
        LOG.error("Unable to create parent directory {}", path);
        return false;
      }
    }
    return mkdirsInternal(path);
  }

  @Override
  public InputStream open(String path) throws IOException {
    return new SwiftInputStream(mAccount, mContainerName, stripPrefixIfPresent(path));
  }

  @Override
  public boolean renameDirectory(String src, String dst) throws IOException {
    String[] children = list(src);
    if (children == null) {
      LOG.error("Failed to list directory {}, aborting rename.", src);
      return false;
    }
    if (isFile(dst) || isDirectory(dst)) {
      LOG.error("Unable to rename {} to {} because destination already exists.", src, dst);
      return false;
    }

    // Source exists and is a directory, and destination does not exist
    // Rename the source folder first
    if (!copy(convertToFolderName(src), convertToFolderName(dst))) {
      return false;
    }
    // Rename each child in the source folder to destination/child
    for (String child : children) {
      String childSrcPath = PathUtils.concatPath(src, child);
      String childDstPath = PathUtils.concatPath(dst, child);
      boolean success;
      if (isDirectory(childSrcPath)) {
        // Recursive call
        success = renameDirectory(childSrcPath, childDstPath);
      } else {
        success = renameFile(childSrcPath, childDstPath);
      }
      if (!success) {
        LOG.error("Failed to rename path {}, aborting rename.", child);
        return false;
      }
    }
    // Delete src and everything under src
    return deleteDirectory(src, DeleteOptions.defaults().setRecursive(true));
  }

  @Override
  public boolean renameFile(String src, String dst) throws IOException {
    if (!isFile(src)) {
      LOG.error("Unable to rename {} to {} because source does not exist or is a directory.",
          src, dst);
      return false;
    }
    if (isFile(dst) || isDirectory(dst)) {
      LOG.error("Unable to rename {} to {} because destination already exists.", src, dst);
      return false;
    }
    String strippedSourcePath = stripPrefixIfPresent(src);
    String strippedDestinationPath = stripPrefixIfPresent(dst);
    return copy(strippedSourcePath, strippedDestinationPath) && deleteFile(src);
  }

  // Setting Swift owner via Alluxio is not supported yet. This is a no-op.
  @Override
  public void setOwner(String path, String user, String group) {}

  // Setting Swift mode via Alluxio is not supported yet. This is a no-op.
  @Override
  public void setMode(String path, short mode) throws IOException {}

  // Returns the account owner.
  @Override
  public String getOwner(String path) throws IOException {
    return mAccountOwner;
  }

  // No group in Swift ACL, returns the account owner.
  @Override
  public String getGroup(String path) throws IOException {
    return mAccountOwner;
  }

  // Returns the account owner's permission mode to the Swift container.
  @Override
  public short getMode(String path) throws IOException {
    return mAccountMode;
  }

  /**
   * A trailing {@link SwiftUnderFileSystem#FOLDER_SUFFIX} is added if not present.
   *
   * @param path URI to the object
   * @return folder path
   */
  private String addFolderSuffixIfNotPresent(final String path) {
    return PathUtils.normalizePath(path, FOLDER_SUFFIX);
  }

  /**
   * Appends the directory suffix ands strips container prefix from path.
   *
   * @param  path the path to convert
   * @return path as a directory path
   */
  private String convertToFolderName(String path) {
    return addFolderSuffixIfNotPresent(stripPrefixIfPresent(path));
  }

  /**
   * Copies an object to another name. Destination will be overwritten if it already exists.
   *
   * @param source the source path to copy
   * @param destination the destination path to copy to
   * @return true if the operation was successful, false otherwise
   */
  private boolean copy(String source, String destination) {
    LOG.debug("copy from {} to {}", source, destination);
    final String strippedSourcePath = stripPrefixIfPresent(source);
    final String strippedDestinationPath = stripPrefixIfPresent(destination);
    // Retry copy for a few times, in case some Swift internal errors happened during copy.
    for (int i = 0; i < NUM_RETRIES; i++) {
      try {
        Container container = mAccount.getContainer(mContainerName);
        container.getObject(strippedSourcePath)
            .copyObject(container, container.getObject(strippedDestinationPath));
        return true;
      } catch (CommandException e) {
        LOG.error("Source path {} does not exist", source);
        return false;
      } catch (Exception e) {
        LOG.error("Failed to copy file {} to {}", source, destination, e.getMessage());
        if (i != NUM_RETRIES - 1) {
          LOG.error("Retrying copying file {} to {}", source, destination);
        }
      }
    }
    LOG.error("Failed to copy file {} to {}, after {} retries", source, destination, NUM_RETRIES);
    return false;
  }

  /**
   * Deletes an object if it exists.
   *
   * @param object object handle to delete
   * @return true if object deletion was successful
   */
  private boolean deleteObject(final StoredObject object) {
    try {
      object.delete();
      return true;
    } catch (CommandException e) {
      LOG.debug("Object {} not found", object.getPath());
    }
    return false;
  }

  /**
   * Check if the object at given path exists. The object could be either a file or directory.
   * @param path path of object
   * @return true if the object exists
   */
  private boolean doesObjectExist(String path) {
    boolean exist = false;
    try {
      exist = getObject(path).exists();
    } catch (CommandException e) {
      LOG.debug("Error getting object details for {}", path);
    }
    return exist;
  }

  /**
   * Get container name from AlluxioURI.
   * @param uri URI used to construct Swift UFS
   * @return the container name from the given uri
   */
  protected static String getContainerName(AlluxioURI uri) {
    //Authority contains the user, host and port portion of a URI
    return uri.getAuthority();
  }

  /**
   * Retrieves a handle to an object identified by the given path.
   *
   * @param path the path to retrieve an object handle for
   * @return the object handle
   */
  private StoredObject getObject(final String path) {
    Container container = mAccount.getContainer(mContainerName);
    return container.getObject(stripPrefixIfPresent(path));
  }

  @Override
  protected String getRootKey() {
    return Constants.HEADER_SWIFT + mContainerName + PATH_SEPARATOR;
  }

  /**
   * Lists the files or folders in the given path, not including the path itself.
   *
   * @param path the folder path whose children are listed
   * @param recursive whether to do a recursive listing
   * @return a collection of the files or folders in the given path, or null if path is a file or
   * does not exist
   * @throws IOException if path is not accessible, e.g. network issues
   */
  private String[] listHelper(String path, boolean recursive) throws IOException {
    String prefix = PathUtils.normalizePath(stripPrefixIfPresent(path), PATH_SEPARATOR);
    prefix = CommonUtils.stripPrefixIfPresent(prefix, PATH_SEPARATOR);

    Collection<DirectoryOrObject> objects = listInternal(prefix, recursive);
    Set<String> children = new HashSet<>();
    final String self = stripFolderSuffixIfPresent(prefix);
    boolean foundSelf = false;
    for (DirectoryOrObject object : objects) {
      String child = stripFolderSuffixIfPresent(object.getName());
      String noPrefix = CommonUtils.stripPrefixIfPresent(child, prefix);
      if (!noPrefix.equals(self)) {
        children.add(noPrefix);
      } else {
        foundSelf = true;
      }
    }

    if (isRoot(path)) {
      foundSelf = true;
    }

    if (!foundSelf) {
      if (mSimulationMode) {
        if (children.size() != 0 || isDirectory(path)) {
          // In simulation mode, the JOSS listDirectory call does not return the prefix itself,
          // so we need the extra isDirectory call
          foundSelf = true;
        }
      }

      if (!foundSelf) {
        // Path does not exist
        return null;
      }
    }

    return children.toArray(new String[children.size()]);
  }

  /**
   * Lists the files or folders which match the given prefix using pagination.
   *
   * @param prefix the prefix to match
   * @param recursive whether to do a recursive listing
   * @return a collection of the files or folders matching the prefix, or null if not found
   * @throws IOException if path is not accessible, e.g. network issues
   */
  private Collection<DirectoryOrObject> listInternal(final String prefix, boolean recursive)
      throws IOException {
    // TODO(adit): UnderFileSystem interface should be changed to support pagination
    ArrayDeque<DirectoryOrObject> results = new ArrayDeque<>();
    Container container = mAccount.getContainer(mContainerName);
    PaginationMap paginationMap = container.getPaginationMap(prefix, LISTING_LENGTH);
    for (int page = 0; page < paginationMap.getNumberOfPages(); page++) {
      if (!recursive) {
        // If not recursive, use delimiter to limit results fetched
        results.addAll(container.listDirectory(paginationMap.getPrefix(), PATH_SEPARATOR_CHAR,
            paginationMap.getMarker(page), paginationMap.getPageSize()));
      } else {
        results.addAll(container.list(paginationMap, page));
      }
    }
    return results;
  }

  /**
   * Creates a directory flagged file with the folder suffix.
   *
   * @param path the path to create a folder
   * @return true if the operation was successful, false otherwise
   */
  private boolean mkdirsInternal(String path) {
    try {
      // We do not check if a file with same name exists, i.e. a file with name
      // 'swift://swift-container/path' and a folder with name 'swift://swift-container/path/'
      // may both exist simultaneously
      createOutputStream(addFolderSuffixIfNotPresent(path)).close();
      return true;
    } catch (IOException e) {
      LOG.error("Failed to create directory: {}", path, e);
      return false;
    }
  }

  /**
   * Strips the folder suffix if it exists. This is a string manipulation utility and does not
   * guarantee the existence of the folder. This method will leave paths without a suffix unaltered.
   *
   * @param path the path to strip the suffix from
   * @return the path with the suffix removed, or the path unaltered if the suffix is not present
   */
  private String stripFolderSuffixIfPresent(final String path) {
    return CommonUtils.stripSuffixIfPresent(path, FOLDER_SUFFIX);
  }
}
