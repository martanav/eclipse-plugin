/*
 * Copyright (c) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.zephyrproject.ide.eclipse.ui.wizards;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.model.IPathEntry;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.tools.templates.core.IGenerator;
import org.eclipse.tools.templates.ui.TemplateWizard;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.zephyrproject.ide.eclipse.core.ZephyrApplicationNewProjectGenerator;
import org.zephyrproject.ide.eclipse.core.ZephyrConstants;
import org.zephyrproject.ide.eclipse.core.ZephyrPlugin;
import org.zephyrproject.ide.eclipse.core.ZephyrStrings;
import org.zephyrproject.ide.eclipse.ui.wizards.internal.ZephyrApplicationBoardWizardPage;
import org.zephyrproject.ide.eclipse.ui.wizards.internal.ZephyrApplicationMainWizardPage;
import org.zephyrproject.ide.eclipse.ui.wizards.internal.ZephyrApplicationToolchainWizardPage;

public class ZephyrApplicationNewProjectWizard extends TemplateWizard {

	private ZephyrApplicationMainWizardPage mainPage;

	private ZephyrApplicationToolchainWizardPage toolchainPage;

	private ZephyrApplicationBoardWizardPage boardPage;

	private final String wizardName =
			ZephyrStrings.ZEPHYR_APPLICATION_PROJECT + " Wizard";

	private ZephyrApplicationNewProjectGenerator generator;

	public ZephyrApplicationNewProjectWizard() {
		super();
		setDialogSettings(ZephyrPlugin.getDefault().getDialogSettings());
		setNeedsProgressMonitor(true);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.jface.wizard.Wizard#setContainer(org.eclipse.jface.wizard.
	 * IWizardContainer)
	 */
	@Override
	public void setContainer(IWizardContainer wizardContainer) {
		super.setContainer(wizardContainer);
		setWindowTitle(ZephyrStrings.ZEPHYR_APPLICATION);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.jface.wizard.Wizard#addPages()
	 */
	@Override
	public void addPages() {
		mainPage = new ZephyrApplicationMainWizardPage(this.wizardName);
		mainPage.setTitle(ZephyrStrings.ZEPHYR_APPLICATION_PROJECT);
		mainPage.setDescription(
				"Create a new " + ZephyrStrings.ZEPHYR_APPLICATION);
		addPage(mainPage);

		toolchainPage =
				new ZephyrApplicationToolchainWizardPage(this.wizardName);
		toolchainPage.setTitle(ZephyrStrings.ZEPHYR_APPLICATION_PROJECT
				+ " - Toolchain Selection");
		toolchainPage.setDescription(
				"Specify the Toolchain to Build this Application");
		addPage(toolchainPage);

		boardPage =
				new ZephyrApplicationBoardWizardPage(this.wizardName, mainPage);
		boardPage.setTitle(ZephyrStrings.ZEPHYR_APPLICATION_PROJECT
				+ " - Target Board Configuration");
		boardPage.setDescription("Specify the target board configuration");
		addPage(boardPage);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.tools.templates.ui.TemplateWizard#getGenerator()
	 */
	@Override
	protected IGenerator getGenerator() {
		if (generator != null) {
			return generator;
		}

		generator = new ZephyrApplicationNewProjectGenerator(
				"templates/ZephyrApplication/template.xml"); //$NON-NLS-1$
		generator.setProjectName(mainPage.getProjectName());
		if (!mainPage.useDefaults()) {
			generator.setLocationURI(mainPage.getLocationURI());
		}
		return generator;
	}

	/**
	 * Show an error dialog and delete the project from workspace.
	 *
	 * Project must be created in the workspace before configuration of
	 * the project can take place. The configuration phase may not complete
	 * entirely, so this is to avoid creating an incomplete/invalid project in
	 * the workspace.
	 *
	 * @param msg The message to be displayed in the dialog.
	 * @param t Throwable that can be displayed in the dialog.
	 */
	private void showErrorDialogAndDeleteProject(String msg, Throwable t) {
		Status status = new Status(IStatus.ERROR, ZephyrPlugin.PLUGIN_ID, 0,
				t.getLocalizedMessage(), t);
		ErrorDialog.openError(getShell(), "Error", msg, status);

		try {
			mainPage.getProjectHandle().delete(false, false, null);
		} catch (CoreException ce) {
			/* ignore */
		}
	}

	/**
	 * Perform actions associated with finishing the wizard.
	 */
	@Override
	public boolean performFinish() {
		/*
		 * TemplateWizard.performFinish() always return true, but would throw
		 * RuntimeException.
		 */
		try {
			super.performFinish();
		} catch (RuntimeException e) {
			showErrorDialogAndDeleteProject("Cannot create project files", e);
			return false;
		}

		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IProject project =
				workspace.getRoot().getProject(mainPage.getProjectName());
		ICProject cProj =
				CCorePlugin.getDefault().getCoreModel().create(project);

		List<IPathEntry> entries = new ArrayList<>();
		try {
			entries = new ArrayList<>(Arrays.asList(cProj.getRawPathEntries()));
		} catch (CModelException e) {
			showErrorDialogAndDeleteProject("Error getting paths from CDT", e);
			return false;
		}

		/*
		 * Create the build directory, and let CDT know where the build (output)
		 * directory is, excluding CMake directories.
		 */
		IFolder buildFolder =
				project.getFolder(ZephyrConstants.DEFAULT_BUILD_DIR);
		if (!buildFolder.exists()) {
			try {
				buildFolder.create(IResource.FORCE | IResource.DERIVED, true,
						new NullProgressMonitor());
			} catch (CoreException e) {
				showErrorDialogAndDeleteProject("Cannot create build directory",
						e);
				return false;
			}
		}

		entries.add(CoreModel.newOutputEntry(buildFolder.getFullPath(),
				new IPath[] {
					new Path("**/CMakeFiles/**") //$NON-NLS-1$
				}));

		/*
		 * Create a link to ZEPHYR_BASE so the indexer can also index the Zephyr
		 * core code.
		 */
		IFolder zBase = project.getFolder("ZEPHYR_BASE"); //$NON-NLS-1$
		String zBaseLoc = mainPage.getZephyrBaseLocation();
		IPath zBaseLink = new Path(zBaseLoc);

		if (workspace.validateLinkLocation(zBase, zBaseLink).isOK()) {
			try {
				zBase.createLink(zBaseLink, IResource.NONE, null);
			} catch (CoreException e) {
				showErrorDialogAndDeleteProject(
						String.format("Error creating linked resource to %s",
								ZephyrConstants.ZEPHYR_BASE_DESC_DIR),
						e);
				return false;
			}
		} else {
			RuntimeException e = new RuntimeException("Link not valid");
			showErrorDialogAndDeleteProject(
					String.format("Error creating linked resource to %s",
							ZephyrConstants.ZEPHYR_BASE_DESC_DIR),
					e);
			return false;
		}

		/*
		 * Also need to tell CDT ZEPHYR_BASE is source so it will index the
		 * source inside.
		 */
		IPath[] exclusion = new Path[] {
			new Path("cmake/**"), //$NON-NLS-1$
			new Path("doc/**"), //$NON-NLS-1$
			new Path("samples/**"), //$NON-NLS-1$
			new Path("sanity-out/**"), //$NON-NLS-1$
			new Path("scripts/**"), //$NON-NLS-1$
			new Path("tests/**"), //$NON-NLS-1$
			new Path("**/build/**"), //$NON-NLS-1$
			new Path("**/CMakeLists.txt") //$NON-NLS-1$
		};

		entries.add(CoreModel.newSourceEntry(zBase.getFullPath(), exclusion));

		try {
			cProj.setRawPathEntries(entries.toArray(new IPathEntry[0]), null);
		} catch (CModelException e) {
			showErrorDialogAndDeleteProject("Error setting paths to CDT", e);
			return false;
		}

		try {
			mainPage.performFinish(project);
			toolchainPage.performFinish(project);
			boardPage.performFinish(project);
		} catch (IOException e) {
			showErrorDialogAndDeleteProject("Cannot save project settings", e);
			return false;
		}

		return true;
	}

	/**
	 * This sets the icon for the wizard page.
	 */
	@Override
	protected void initializeDefaultPageImageDescriptor() {
		ImageDescriptor desc = AbstractUIPlugin.imageDescriptorFromPlugin(
				ZephyrPlugin.PLUGIN_ID, "icons/wizard.png"); //$NON-NLS-1$
		setDefaultPageImageDescriptor(desc);
	}

}
