/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.authentication.ProxyAuthenticater;
import org.archicontribs.modelrepository.authentication.SimpleCredentialsStorage;
import org.archicontribs.modelrepository.dialogs.NewModelRepoDialog;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.preferences.IPreferenceConstants;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateModel;

/**
 * Create an online repo from an existing model
 * 
 * @author Phillip Beauvoir
 */
public class CreateRepoFromModelAction extends AbstractModelAction {
    
    private IArchimateModel fModel;
	
    public CreateRepoFromModelAction(IWorkbenchWindow window, IArchimateModel model) {
        super(window);
        
        fModel = model;
        
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_CREATE_REPOSITORY));
        setText(Messages.CreateRepoFromModelAction_0);
        setToolTipText(Messages.CreateRepoFromModelAction_0);
    }

    @Override
    public void run() {
        NewModelRepoDialog dialog = new NewModelRepoDialog(fWindow.getShell());
        if(dialog.open() != Window.OK) {
            return;
        }
        
        final String modelName = dialog.getModelName();
        final String repoURL = dialog.getURL() + modelName + ".git"; //$NON-NLS-1$
        final String userName = dialog.getUsername();
        final String userPassword = dialog.getPassword();
        
//        final String modelName = "ModelName";
//        final String repoURL = "https://localhost:8443/r/" + modelName + ".git";
//        final String userName = "admin";
//        final String userPassword = "admin";
        
        if(!StringUtils.isSet(repoURL) && !StringUtils.isSet(userName) && !StringUtils.isSet(userPassword)) {
            return;
        }
        
        File localRepoFolder = new File(ModelRepositoryPlugin.INSTANCE.getUserModelRepositoryFolder(),
                GraficoUtils.getLocalGitFolderName(repoURL));
        
        // Folder is not empty
        if(localRepoFolder.exists() && localRepoFolder.isDirectory() && localRepoFolder.list().length > 0) {
            MessageDialog.openError(fWindow.getShell(),
                    Messages.CreateRepoFromModelAction_1,
                    Messages.CreateRepoFromModelAction_2 +
                    " " + localRepoFolder.getAbsolutePath()); //$NON-NLS-1$

            return;
        }
        
        setLocalRepositoryFolder(localRepoFolder);

        class Progress extends EmptyProgressMonitor implements IRunnableWithProgress {
            private IProgressMonitor monitor;

            @Override
            public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                try {
                    this.monitor = monitor;
                    
                    monitor.beginTask(Messages.CreateRepoFromModelAction_3, IProgressMonitor.UNKNOWN);
                    
                    // Proxy check
                    ProxyAuthenticater.update(repoURL);
                    
                    // Create a new repo
                    try(Git git = GraficoUtils.createNewLocalGitRepository(getLocalRepositoryFolder(), repoURL)) {
                    }
                    
                    // TODO: If the model has not been saved yet this is fine but if the model already exists
                    // We should tell the user this is the case
                    
                    // Set new file location
                    fModel.setFile(GraficoUtils.getModelFileName(getLocalRepositoryFolder()));
                    
                    // And Save it
                    IEditorModelManager.INSTANCE.saveModel(fModel);
                    
                    // Export to Grafico
                    exportModelToGraficoFiles();
                    
                    monitor.subTask(Messages.CreateRepoFromModelAction_4);

                    // Commit changes
                    String author = ModelRepositoryPlugin.INSTANCE.getPreferenceStore().getString(IPreferenceConstants.PREFS_COMMIT_USER_NAME);
                    String email = ModelRepositoryPlugin.INSTANCE.getPreferenceStore().getString(IPreferenceConstants.PREFS_COMMIT_USER_EMAIL);
                    GraficoUtils.commitChanges(getLocalRepositoryFolder(), new PersonIdent(author, email), Messages.CreateRepoFromModelAction_5);
                    
                    monitor.subTask(Messages.CreateRepoFromModelAction_6);
                    
                    // Push
                    GraficoUtils.pushToRemote(getLocalRepositoryFolder(), userName, userPassword, null);
                    
                    // Store repo credentials if option is set
                    if(ModelRepositoryPlugin.INSTANCE.getPreferenceStore().getBoolean(IPreferenceConstants.PREFS_STORE_REPO_CREDENTIALS)) {
                        SimpleCredentialsStorage sc = new SimpleCredentialsStorage(new File(getLocalRepositoryFolder(), ".git"), IGraficoConstants.REPO_CREDENTIALS_FILE); //$NON-NLS-1$
                        sc.store(userName, userPassword);
                    }
                }
                catch(GitAPIException | IOException | NoSuchAlgorithmException | URISyntaxException ex) {
                    displayErrorDialog(Messages.CreateRepoFromModelAction_7, ex);
                }
                finally {
                    monitor.done();
                }
            }

            @Override
            public void beginTask(String title, int totalWork) {
                monitor.subTask(title);
            }

            @Override
            public boolean isCancelled() {
                return monitor.isCanceled();
            }
        }
        
        Display.getCurrent().asyncExec(new Runnable() {
            @Override
            public void run() {
                try {
                    ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(fWindow.getShell());
                    pmDialog.run(false, true, new Progress());
                }
                catch(InvocationTargetException | InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        });
    }
}
