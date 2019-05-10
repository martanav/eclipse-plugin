/*
 * Copyright (c) 2015, 2016 QNX Software Systems and others.
 * Copyright (c) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.zephyrproject.ide.eclipse.core.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.cdt.core.ConsoleOutputStream;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IConsoleParser;
import org.eclipse.cdt.core.build.CBuildConfiguration;
import org.eclipse.cdt.core.build.IToolChain;
import org.eclipse.cdt.core.model.ICModelMarker;
import org.eclipse.cdt.core.resources.IConsole;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.zephyrproject.ide.eclipse.core.ZephyrConstants;
import org.zephyrproject.ide.eclipse.core.ZephyrPlugin;
import org.zephyrproject.ide.eclipse.core.internal.ZephyrHelpers;

/**
 * Build configuration for Zephyr Application
 *
 * This contains code to actually build and clean the project.
 *
 * Originally from org.eclipse.cdt.cmake.core.internal.CMakeBuildConfiguration.
 */
public class ZephyrApplicationBuildConfiguration extends CBuildConfiguration {

	@Override
	public IContainer getBuildContainer() throws CoreException {
		IProject project = getProject();
		IFolder buildFolder =
				project.getFolder(ZephyrConstants.DEFAULT_BUILD_DIR);
		if (!buildFolder.exists()) {
			buildFolder.create(IResource.FORCE | IResource.DERIVED, true,
					new NullProgressMonitor());
		}

		return buildFolder;
	}

	public ZephyrApplicationBuildConfiguration(IBuildConfiguration config,
			String name) throws CoreException {
		super(config, name);
	}

	public ZephyrApplicationBuildConfiguration(IBuildConfiguration config,
			IToolChain toolChain) {
		super(config, toolChain);
	}

	public ZephyrApplicationBuildConfiguration(IBuildConfiguration config,
			String name, IToolChain toolChain) {
		super(config, name, toolChain);
	}

	public ZephyrApplicationBuildConfiguration(IBuildConfiguration config,
			String name, IToolChain toolChain, String launchMode) {
		super(config, name, toolChain, launchMode);
	}

	/**
	 * Get the board name to be built for.
	 *
	 * @param project The Project.
	 * @return Name of board to be built for.
	 */
	private String getBoardName(IProject project) {
		ScopedPreferenceStore pStore = new ScopedPreferenceStore(
				new ProjectScope(project), ZephyrPlugin.PLUGIN_ID);

		return pStore.getString(ZephyrConstants.ZEPHYR_BOARD);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.cdt.core.build.ICBuildConfiguration#build(int,
	 * java.util.Map, org.eclipse.cdt.core.resources.IConsole,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public IProject[] build(int kind, Map<String, String> args,
			IConsole console, IProgressMonitor monitor) throws CoreException {
		IProject project = getProject();

		try {
			/* Remove C-related warnings/errors */
			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false,
					IResource.DEPTH_INFINITE);

			ConsoleOutputStream consoleOut = console.getOutputStream();

			Path buildDir = getBuildDirectory();

			String boardName = getBoardName(project);

			String projectAbsPath =
					new File(project.getLocationURI()).getAbsolutePath();

			consoleOut.write(String.format(
					"Building Zephyr Application project %s for board %s\n",
					project.getName(), boardName));

			if (!Files.exists(buildDir.resolve("CMakeFiles")) //$NON-NLS-1$
					|| (kind == IncrementalProjectBuilder.FULL_BUILD)) {
				List<String> command = new ArrayList<>();

				Path cmakePath = findCommand("cmake"); //$NON-NLS-1$
				if (cmakePath != null) {
					command.add(cmakePath.toString());
				} else {
					/* Hope this is in path */
					command.add("cmake"); //$NON-NLS-1$
				}

				command.add(String.format("-DBOARD=%s", boardName));

				command.add(projectAbsPath);

				ProcessBuilder processBuilder = new ProcessBuilder(command)
						.directory(buildDir.toFile());
				Map<String, String> env = processBuilder.environment();
				ZephyrHelpers.setupBuildCommandEnvironment(project, env);
				setBuildEnvironment(env);
				Process process = processBuilder.start();
				consoleOut.write(String.join(" ", command) + '\n'); //$NON-NLS-1$
				watchProcess(process, new IConsoleParser[0], console);
			}

			try (ErrorParserManager epm =
					new ErrorParserManager(project, getBuildDirectoryURI(),
							this, getToolChain().getErrorParserIds())) {
				String[] command = {
					"make" //$NON-NLS-1$
				};

				Path cmdPath = findCommand(command[0]);
				if (cmdPath != null) {
					command[0] = cmdPath.toString();
				}

				ProcessBuilder processBuilder = new ProcessBuilder(command)
						.directory(buildDir.toFile());
				Map<String, String> env = processBuilder.environment();
				ZephyrHelpers.setupBuildCommandEnvironment(project, env);
				setBuildEnvironment(env);
				Process process = processBuilder.start();
				consoleOut.write(String.join(" ", command) + '\n'); //$NON-NLS-1$
				watchProcess(process, new IConsoleParser[] {
					epm
				}, console);
			}

			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

			return new IProject[] {
				project
			};
		} catch (IOException eio) {
			throw new CoreException(ZephyrHelpers.errorStatus(String.format(
					"Error building Zephyr Application project %s!",
					project.getName()), eio));
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.cdt.core.build.ICBuildConfiguration#clean(org.eclipse.cdt.
	 * core.resources.IConsole, org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void clean(IConsole console, IProgressMonitor monitor)
			throws CoreException {
		IProject project = getProject();

		try {
			/* Remove C-related warnings/errors */
			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false,
					IResource.DEPTH_INFINITE);

			/* Grab the board name from project */
			ScopedPreferenceStore pStore = new ScopedPreferenceStore(
					new ProjectScope(project), ZephyrPlugin.PLUGIN_ID);

			Path buildDir = getBuildDirectory();

			if (!Files.exists(buildDir.resolve("CMakeFiles"))) {
				/* Haven't run CMake yet, so nothing to clean */
				return;
			}

			String boardName = pStore.getString(ZephyrConstants.ZEPHYR_BOARD);

			ConsoleOutputStream consoleOut = console.getOutputStream();

			consoleOut.write(String.format(
					"Cleaning Zephyr Application project %s for board %s\n",
					project.getName(), boardName));

			String[] command = {
				"make", //$NON-NLS-1$
				"clean" //$NON-NLS-1$
			};

			Path cmdPath = findCommand(command[0]);
			if (cmdPath != null) {
				command[0] = cmdPath.toString();
			}

			ProcessBuilder processBuilder =
					new ProcessBuilder(command).directory(buildDir.toFile());
			Map<String, String> env = processBuilder.environment();
			setupCommandEnvironment(project, env);
			setBuildEnvironment(env);
			Process process = processBuilder.start();
			consoleOut.write(String.join(" ", command) + '\n'); //$NON-NLS-1$
			watchProcess(process, new IConsoleParser[0], console);

			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		} catch (IOException eio) {
			throw new CoreException(ZephyrHelpers.errorStatus(String.format(
					"Error cleaning Zephyr Application project %s!",
					project.getName()), eio));
		}
	}
}
