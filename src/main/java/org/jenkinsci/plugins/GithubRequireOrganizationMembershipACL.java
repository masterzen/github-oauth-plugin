/**
 The MIT License

Copyright (c) 2011 Michael O'Cleirigh

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.



 */
package org.jenkinsci.plugins;

import hudson.model.AbstractItem;
import hudson.model.Describable;
import hudson.model.Item;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.security.ACL;
import hudson.security.Permission;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMNavigatorOwner;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.triggers.SCMTriggerItem;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author Mike
 *
 */
public class GithubRequireOrganizationMembershipACL extends ACL {

    private static final Logger log = Logger
            .getLogger(GithubRequireOrganizationMembershipACL.class.getName());

    private final List<String> organizationNameList;
    private final List<String> adminUserNameList;
    private final boolean authenticatedUserReadPermission;
    private final boolean useRepositoryPermissions;
    private final boolean authenticatedUserCreateJobPermission;
    private final boolean allowGithubWebHookPermission;
    private final boolean allowCcTrayPermission;
    private final boolean allowAnonymousReadPermission;
    private final boolean allowAnonymousJobStatusPermission;
    private final AbstractItem item;

    /*
     * (non-Javadoc)
     *
     * @see hudson.security.ACL#hasPermission(org.acegisecurity.Authentication,
     * hudson.security.Permission)
     */
    @Override
    public boolean hasPermission(@Nonnull Authentication a, @Nonnull Permission permission) {
        if (a instanceof GithubAuthenticationToken) {
            if (!a.isAuthenticated())
                return false;

            GithubAuthenticationToken authenticationToken = (GithubAuthenticationToken) a;

            String candidateName = a.getName();

            if (adminUserNameList.contains(candidateName)) {
                // if they are an admin then they have permission
                log.finest("Granting Admin rights to user " + candidateName);
                return true;
            }

            if (this.item != null) {
                if (useRepositoryPermissions) {
                    if(hasRepositoryPermission(authenticationToken, permission)) {
                        log.finest("Granting Authenticated User " + permission.getId() +
                            " permission on project " + item.getName() +
                            "to user " + candidateName);
                        return true;
                    }
                } else {
                    if (authenticatedUserReadPermission) {
                        if (checkReadPermission(permission)) {
                            log.finest("Granting Authenticated User read permission " +
                                "on project " + item.getName() +
                                "to user " + candidateName);
                            return true;
                        }
                    }
                }
            } else if (authenticatedUserReadPermission) {
                if (checkReadPermission(permission)) {
                    // if we support authenticated read and this is a read
                    // request we allow it
                    log.finest("Granting Authenticated User read permission to user "
                            + candidateName);
                return true;
                }
            }

            if (authenticatedUserCreateJobPermission && permission.equals(Item.CREATE)) {
                return true;
            }

            for (String organizationName : this.organizationNameList) {
                if (authenticationToken.hasOrganizationPermission(
                        candidateName, organizationName)) {

                    String[] parts = permission.getId().split("\\.");

                    String test = parts[parts.length - 1].toLowerCase();

                    if (checkReadPermission(permission)
                            || testBuildPermission(permission)) {
                        // check the permission

                        log.finest("Granting READ and BUILD rights to user "
                                + candidateName + " a member of "
                                + organizationName);
                        return true;
                    }
                }
            }

            // no match.
            return false;
        } else {
            String authenticatedUserName = a.getName();
            if (authenticatedUserName == null) {
                throw new IllegalArgumentException("Authentication must have a valid name");
            }

            if (authenticatedUserName.equals(SYSTEM.getPrincipal())) {
                // give system user full access
                log.finest("Granting Full rights to SYSTEM user.");
                return true;
            }

            if (authenticatedUserName.equals("anonymous")) {
                if(checkJobStatusPermission(permission) && allowAnonymousJobStatusPermission) {
                    return true;
                }

                if (checkReadPermission(permission)) {
                    if (allowAnonymousReadPermission) {
                        return true;
                    }
                    if (allowGithubWebHookPermission &&
                            (currentUriPathEquals("github-webhook") ||
                             currentUriPathEquals("github-webhook/"))) {
                        log.finest("Granting READ access for github-webhook url: " + requestURI());
                        return true;
                    }
                    if (allowCcTrayPermission && currentUriPathEndsWithSegment("cc.xml")) {
                        log.finest("Granting READ access for cctray url: " + requestURI());
                        return true;
                    }
                    log.finer("Denying anonymous READ permission to url: " + requestURI());
                }
                return false;
            }

            if (adminUserNameList.contains(authenticatedUserName)) {
                // if they are an admin then they have all permissions
                log.finest("Granting Admin rights to user " + a.getName());
                return true;
            }

            // else:
            // deny request
            //
            return false;
        }
    }

    private boolean currentUriPathEquals( String specificPath ) {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IllegalStateException("Jenkins not started");
        }
        String rootUrl = jenkins.getRootUrl();
        if (rootUrl == null) {
            throw new IllegalStateException("Could not determine Jenkins URL");
        }
        String requestUri = requestURI();
        if (requestUri != null) {
            String basePath = URI.create(rootUrl).getPath();
            return URI.create(requestUri).getPath().equals(basePath + specificPath);
        } else {
            return false;
        }
    }

    private boolean currentUriPathEndsWithSegment( String segment ) {
        String requestUri = requestURI();
        if (requestUri != null) {
          return requestUri.substring(requestUri.lastIndexOf('/') + 1).equals(segment);
        } else {
            return false;
        }
    }

    private String requestURI() {
        StaplerRequest currentRequest = Stapler.getCurrentRequest();
        return (currentRequest == null) ? null : currentRequest.getOriginalRequestURI();
    }

    private boolean testBuildPermission(Permission permission) {
        if (permission.getId().equals("hudson.model.Hudson.Build")
                || permission.getId().equals("hudson.model.Item.Build")) {
            return true;
        } else {
            return false;
        }
    }

    private boolean checkReadPermission(Permission permission) {
        if (permission.getId().equals("hudson.model.Hudson.Read")
                || permission.getId().equals("hudson.model.Item.Discover")
                || permission.getId().equals("hudson.model.Item.Workspace")
                || permission.getId().equals("hudson.model.Item.Read")) {
            return true;
        } else {
            return false;
        }
    }

    private boolean checkJobStatusPermission(Permission permission) {
        return permission.getId().equals("hudson.model.Item.ViewStatus");
    }

    public boolean hasRepositoryPermission(GithubAuthenticationToken authenticationToken, Permission permission) {
        List<String> repositoryNames = getRepositoryNames();

        if (repositoryNames.isEmpty()) {
            if (authenticatedUserCreateJobPermission) {
                if (permission.equals(Item.READ) ||
                        permission.equals(Item.DISCOVER) ||
                        permission.equals(Item.CONFIGURE) ||
                        permission.equals(Item.DELETE) ||
                        permission.equals(Item.EXTENDED_READ) ||
                        permission.equals(Item.CANCEL)) {
                    return true;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }

        for (String repositoryName : repositoryNames) {
            if (checkReadPermission(permission) &&
                authenticationToken.isPublicRepository(repositoryName)) {
                return true;
            } else {
                if (authenticationToken.hasRepositoryPermission(repositoryName, permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> getRepositoryNames() {
        List<String> repositoryNames = new ArrayList<String>();
        Collection<? extends Describable> scms = null;

        if (this.item instanceof SCMTriggerItem) {
            SCMTriggerItem project = (SCMTriggerItem) item;
            scms = project.getSCMs();
        } else if (this.item instanceof SCMNavigatorOwner) {
            SCMNavigatorOwner project = (SCMNavigatorOwner) item;
            scms = project.getSCMSources();
        } else if (this.item instanceof SCMSourceOwner) {
            SCMSourceOwner project = (SCMSourceOwner) item;
            scms = project.getSCMSources();
        }
        for (Describable scm : scms) {
            String repoUrl = null;
            if (scm instanceof GitHubSCMSource) {
                GitHubSCMSource git = (GitHubSCMSource) scm;
                repoUrl = git.getRemote();
            } else if (scm instanceof GitSCMSource) {
                GitSCMSource git = (GitSCMSource) scm;
                repoUrl = git.getRemote();
            } else if (scm instanceof GitSCM) {
                GitSCM git = (GitSCM) scm;
                List<UserRemoteConfig> userRemoteConfigs = git.getUserRemoteConfigs();
                if (!userRemoteConfigs.isEmpty()) {
                    repoUrl = userRemoteConfigs.get(0).getUrl();
                }
            }
            if (repoUrl != null) {
                GitHubRepositoryName githubRepositoryName =
                    GitHubRepositoryName.create(repoUrl);
                if (githubRepositoryName != null) {
                    repositoryNames.add(githubRepositoryName.userName + "/"
                        + githubRepositoryName.repositoryName);
                }
            }
        }
        return repositoryNames;
    }

    public GithubRequireOrganizationMembershipACL(String adminUserNames,
            String organizationNames,
            boolean authenticatedUserReadPermission,
            boolean useRepositoryPermissions,
            boolean authenticatedUserCreateJobPermission,
            boolean allowGithubWebHookPermission,
            boolean allowCcTrayPermission,
            boolean allowAnonymousReadPermission,
            boolean allowAnonymousJobStatusPermission) {
        super();

        this.authenticatedUserReadPermission      = authenticatedUserReadPermission;
        this.useRepositoryPermissions             = useRepositoryPermissions;
        this.authenticatedUserCreateJobPermission = authenticatedUserCreateJobPermission;
        this.allowGithubWebHookPermission         = allowGithubWebHookPermission;
        this.allowCcTrayPermission                = allowCcTrayPermission;
        this.allowAnonymousReadPermission         = allowAnonymousReadPermission;
        this.allowAnonymousJobStatusPermission    = allowAnonymousJobStatusPermission;
        this.adminUserNameList                    = new LinkedList<String>();

        String[] parts = adminUserNames.split(",");

        for (String part : parts) {
            adminUserNameList.add(part.trim());
        }

        this.organizationNameList = new LinkedList<String>();

        parts = organizationNames.split(",");

        for (String part : parts) {
            organizationNameList.add(part.trim());
        }

        this.item = null;
    }

    public GithubRequireOrganizationMembershipACL cloneForProject(AbstractItem item) {
      return new GithubRequireOrganizationMembershipACL(
          this.adminUserNameList,
          this.organizationNameList,
          this.authenticatedUserReadPermission,
          this.useRepositoryPermissions,
          this.authenticatedUserCreateJobPermission,
          this.allowGithubWebHookPermission,
          this.allowCcTrayPermission,
          this.allowAnonymousReadPermission,
          this.allowAnonymousJobStatusPermission,
          item);
    }

    public GithubRequireOrganizationMembershipACL(List<String> adminUserNameList,
            List<String> organizationNameList,
            boolean authenticatedUserReadPermission,
            boolean useRepositoryPermissions,
            boolean authenticatedUserCreateJobPermission,
            boolean allowGithubWebHookPermission,
            boolean allowCcTrayPermission,
            boolean allowAnonymousReadPermission,
            boolean allowAnonymousJobStatusPermission,
            AbstractItem item) {
        super();

        this.adminUserNameList                    = adminUserNameList;
        this.organizationNameList                 = organizationNameList;
        this.authenticatedUserReadPermission      = authenticatedUserReadPermission;
        this.useRepositoryPermissions             = useRepositoryPermissions;
        this.authenticatedUserCreateJobPermission = authenticatedUserCreateJobPermission;
        this.allowGithubWebHookPermission         = allowGithubWebHookPermission;
        this.allowCcTrayPermission                = allowCcTrayPermission;
        this.allowAnonymousReadPermission         = allowAnonymousReadPermission;
        this.allowAnonymousJobStatusPermission    = allowAnonymousJobStatusPermission;
        this.item = item;
    }

    public List<String> getOrganizationNameList() {
        return organizationNameList;
    }

    public List<String> getAdminUserNameList() {
        return adminUserNameList;
    }

    public boolean isUseRepositoryPermissions() {
        return useRepositoryPermissions;
    }

    public boolean isAuthenticatedUserCreateJobPermission() {
        return authenticatedUserCreateJobPermission;
    }

    public boolean isAuthenticatedUserReadPermission() {
        return authenticatedUserReadPermission;
    }

    public boolean isAllowGithubWebHookPermission() {
        return allowGithubWebHookPermission;
    }

    public boolean isAllowCcTrayPermission() {
        return allowCcTrayPermission;
    }

    /**
     * @return the allowAnonymousReadPermission
     */
    public boolean isAllowAnonymousReadPermission() {
        return allowAnonymousReadPermission;
    }

    public boolean isAllowAnonymousJobStatusPermission() {
        return allowAnonymousJobStatusPermission;
    }
}
