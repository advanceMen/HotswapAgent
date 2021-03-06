/*
 * Copyright 2013-2019 the HotswapAgent authors.
 *
 * This file is part of HotswapAgent.
 *
 * HotswapAgent is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 2 of the License, or (at your
 * option) any later version.
 *
 * HotswapAgent is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with HotswapAgent. If not, see http://www.gnu.org/licenses/.
 */
package org.hotswap.agent.plugin.cxf.jaxrs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.hotswap.agent.annotation.Init;
import org.hotswap.agent.annotation.LoadEvent;
import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.config.PluginConfiguration;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.ClassPool;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtConstructor;
import org.hotswap.agent.javassist.CtMethod;
import org.hotswap.agent.javassist.CtNewConstructor;
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.logging.AgentLogger.Level;
import org.hotswap.agent.util.AnnotationHelper;
import org.hotswap.agent.util.PluginManagerInvoker;
import org.hotswap.agent.util.ReflectionHelper;

/**
 * CxfJAXRS plugin (http://cxf.apache.org/docs/jax-rs.html)
 *
 * @author Vladimir Dvorak
 *
 */
@Plugin(name = "CxfJAXRS",
        description = "CXF-JAXRS plugin for JAXRS CXF frontend. Reload jaxrs resource on resource class change. Reinject resource's injection points.", //
        testedVersions = { "3.2.7" },
        expectedVersions = { "3.2.7" })
public class CxfJAXRSPlugin {

    private static AgentLogger LOGGER = AgentLogger.getLogger(CxfJAXRSPlugin.class);

    private static final String PATH_ANNOTATION = "javax.ws.rs.Path";

    private static final int WAIT_ON_REDEFINE = 300; // Should be bigger then DI plugins (CDI..)
    private static final int WAIT_ON_CREATE = 600; // Should be bigger then DI plugins (CDI..)

    @Init
    ClassLoader appClassLoader;

    @Init
    Scheduler scheduler;

    Map<String, Object> classResourceInfoRegistry = new HashMap<>();
    WeakHashMap<Object, Boolean> serviceInstances = new WeakHashMap<>();

    @Init
    public void init(PluginConfiguration pluginConfiguration) {
        LOGGER.info("CxfJAXRSPlugin initialized.");
    }

    public void registerClassResourceInfo(Class<?> serviceClass, Object classResourceInfo) {
        classResourceInfoRegistry.put(serviceClass.getName(), classResourceInfo);
        LOGGER.debug("Registered service {} ", serviceClass.getClass().getName());
    }

    public boolean containsServiceInstance(Class<?> serviceClass) {
        for (Object provider: serviceInstances.keySet()) {
            if (provider.getClass().getName().equals(serviceClass.getName())) {
                return true;
            }
        }
        return false;
    }

    public List<Object> getServiceInstances(Class<?> serviceClass) {
        List<Object> result = new ArrayList<>();
        for (Object service: serviceInstances.keySet()) {
            if (service.getClass().getName().equals(serviceClass.getName())) {
                result.add(service);
            }
        }
        return result;
    }

    public void registerServiceInstance(Object serviceInstance) {
        serviceInstances.put(serviceInstance, Boolean.TRUE);
    }

    @OnClassLoadEvent(classNameRegexp = "org.apache.cxf.jaxrs.utils.ResourceUtils")
    public static void patchResourceUtils(CtClass ctClass, ClassPool classPool){
        try{
            CtMethod createCriMethods[] = ctClass.getDeclaredMethods("createClassResourceInfo");

            for (CtMethod method: createCriMethods) {
                if (method.getParameterTypes()[0].getName().equals(Class.class.getName())) {
                    method.insertAfter(
                        "if($_ != null && !$_.getClass().getName().contains(\"$$\") ) { " +
                            "ClassLoader $$cl = $1.getClassLoader();" +
                            PluginManagerInvoker.buildInitializePlugin(CxfJAXRSPlugin.class, "$$cl") +
                            "try {" +
                                "org.hotswap.agent.javassist.runtime.Desc.setUseContextClassLoaderLocally();" +
                                "$_ = org.hotswap.agent.plugin.cxf.jaxrs.ClassResourceInfoProxyHelper.createProxy($_, $sig, $args);" +
                            "} finally {"+
                                "org.hotswap.agent.javassist.runtime.Desc.resetUseContextClassLoaderLocally();" +
                            "}" +
                            "if ($_.getClass().getName().contains(\"$$\")) {" +
                                 PluginManagerInvoker.buildCallPluginMethod("$$cl", CxfJAXRSPlugin.class, "registerClassResourceInfo",
                                "$_.getServiceClass()", "java.lang.Class", "$_", "java.lang.Object") +
                            "}" +
                        "}" +
                        "return $_;"
                    );
                }
            }
        } catch(NotFoundException | CannotCompileException e){
            LOGGER.error("Error patching ResourceUtils", e);
        }
    }

    @OnClassLoadEvent(classNameRegexp = "org.apache.cxf.cdi.JAXRSCdiResourceExtension")
    public static void patchCxfJARSCdiExtension(CtClass ctClass, ClassPool classPool){
        try{
            CtMethod loadMethod = ctClass.getDeclaredMethod("load");

            loadMethod.insertAfter( "{ " +
                    "ClassLoader $$cl = this.bus.getClass().getClassLoader();" +
                    "Object $$plugin =" + PluginManagerInvoker.buildInitializePlugin(CxfJAXRSPlugin.class, "$$cl") +
                    "org.hotswap.agent.plugin.cxf.jaxrs.HaCdiExtraCxfContext.registerExtraContext($$plugin);" +
                "}"
            );
    } catch(NotFoundException | CannotCompileException e){
            LOGGER.error("Error patching ResourceUtils", e);
        }
    }

    @OnClassLoadEvent(classNameRegexp = "org.apache.cxf.jaxrs.model.ClassResourceInfo")
    public static void patchClassResourceInfo(CtClass ctClass, ClassPool classPool) {
        try {
            // Add default constructor to simplify proxy creation
            CtConstructor c = CtNewConstructor.make("public " + ctClass.getSimpleName() + "() { super(null); }", ctClass);
            ctClass.addConstructor(c);
        } catch (CannotCompileException e) {
            LOGGER.error("Error patching ClassResourceInfo", e);
        }
    }

    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public void classReload(ClassLoader classLoader, CtClass clazz, Class<?> original) {
        if (AnnotationHelper.hasAnnotation(original, PATH_ANNOTATION)
                || AnnotationHelper.hasAnnotation(clazz, PATH_ANNOTATION)) {
            if(LOGGER.isLevelEnabled(Level.TRACE)) {
                LOGGER.trace("Reload @Path annotated class {}", clazz.getName());
            }
            refreshClass(classLoader, clazz.getName(), original, WAIT_ON_REDEFINE);
        }
    }

    /*
    @OnClassFileEvent(classNameRegexp = ".*", events = { FileEvent.CREATE })
    public void newClass(ClassLoader classLoader, CtClass clazz) throws Exception {
        if (AnnotationHelper.hasAnnotation(clazz, PATH_ANNOTATION)) {
            if(LOGGER.isLevelEnabled(Level.TRACE)) {
                LOGGER.trace("Load @Path annotated class {}", clazz.getName());
            }
            refreshClass(classLoader, clazz.getName(), null, WAIT_ON_CREATE);
        }
    }
    */

    private void refreshClass(ClassLoader classLoader, String className, Class<?> original, int timeout) {
        try {
            Object classResourceInfoProxy = classResourceInfoRegistry.get(className);
            if (classResourceInfoProxy == null) {
                LOGGER.error("refreshClass() ClassResourceInfo proxy not found for classResourceInfo={}.", className);
                return;
            }
            Class<?> cmdClass = Class.forName(CxfJAXRSCommand.class.getName(), true, appClassLoader);
            Command cmd = (Command) cmdClass.newInstance();
            ReflectionHelper.invoke(cmd, cmdClass, "setupCmd", new Class[] { ClassLoader.class, Object.class },
                    classLoader, classResourceInfoProxy);
            scheduler.scheduleCommand(cmd, timeout);
        } catch (Exception e) {
            LOGGER.error("refreshClass() exception {}.", e.getMessage());
        }
    }

}
