package org.jboss.resteasy.core;

import org.jboss.resteasy.resteasy_jaxrs.i18n.Messages;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jboss.resteasy.spi.ApplicationException;
import org.jboss.resteasy.spi.Failure;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.InjectorFactory;
import org.jboss.resteasy.spi.InternalServerErrorException;
import org.jboss.resteasy.spi.MethodInjector;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResourceFactory;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.spi.ResteasyUriInfo;
import org.jboss.resteasy.spi.metadata.ResourceBuilder;
import org.jboss.resteasy.spi.metadata.ResourceClass;
import org.jboss.resteasy.spi.metadata.ResourceLocator;
import org.jboss.resteasy.util.FindAnnotation;
import org.jboss.resteasy.util.GetRestful;

import javax.ws.rs.NotFoundException;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@SuppressWarnings("unchecked")
public class ResourceLocatorInvoker implements ResourceInvoker
{
   protected InjectorFactory injector;
   protected MethodInjector methodInjector;
   protected ResourceFactory resource;
   protected ResteasyProviderFactory providerFactory;
   protected ResourceLocator method;
   protected ConcurrentHashMap<Class, LocatorRegistry> cachedSubresources = new ConcurrentHashMap<Class, LocatorRegistry>();

   public ResourceLocatorInvoker(ResourceFactory resource, InjectorFactory injector, ResteasyProviderFactory providerFactory, ResourceLocator locator)
   {
      this.resource = resource;
      this.injector = injector;
      this.providerFactory = providerFactory;
      this.method = locator;
      this.methodInjector = injector.createMethodInjector(locator, providerFactory);
   }

   protected Object createResource(HttpRequest request, HttpResponse response)
   {
      Object resource = this.resource.createResource(request, response, providerFactory);
      return createResource(request, response, resource);

   }

   protected Object createResource(HttpRequest request, HttpResponse response, Object locator)
   {
      ResteasyUriInfo uriInfo = request.getUri();
      Object[] args = new Object[0];
      RuntimeException lastException = (RuntimeException)request.getAttribute(ResourceMethodRegistry.REGISTRY_MATCHING_EXCEPTION);
      try
      {
         args = methodInjector.injectArguments(request, response);
      }
      catch (NotFoundException failure)
      {
         if (lastException != null) throw lastException;
         throw failure;
      }
      try
      {
         uriInfo.pushCurrentResource(locator);
         Object subResource = method.getMethod().invoke(locator, args);
          if (subResource.getClass() == Class.class) {
              // this is a class injection
              Class<?> subResourceClazz = (Class<?>) subResource;
              subResource = subResourceClazz.getConstructors()[0].newInstance(args);
          }
         return subResource;

      }
      catch (IllegalAccessException e)
      {
         throw new InternalServerErrorException(e);
      }
      catch (InvocationTargetException e)
      {
         throw new ApplicationException(e.getCause());
      } catch (InstantiationException e) {
          throw new ApplicationException(e.getCause());
      }
   }

   public Method getMethod()
   {
      return method.getMethod();
   }

   public BuiltResponse invoke(HttpRequest request, HttpResponse response)
   {
      Object target = createResource(request, response);
      return invokeOnTargetObject(request, response, target);
   }

   public BuiltResponse invoke(HttpRequest request, HttpResponse response, Object locator)
   {
      Object target = createResource(request, response, locator);
      return invokeOnTargetObject(request, response, target);
   }

   protected BuiltResponse invokeOnTargetObject(HttpRequest request, HttpResponse response, Object target)
   {
      if (target == null)
      {
         NotFoundException notFound = new NotFoundException(Messages.MESSAGES.nullSubresource(request.getUri().getAbsolutePath()));
         throw notFound;
      }
      Class<? extends Object> clazz = target.getClass();
      LocatorRegistry registry = cachedSubresources.get(clazz);
      if (registry == null)
      {
         if (!GetRestful.isSubResourceClass(clazz))
         {
            String msg = Messages.MESSAGES.subresourceHasNoJaxRsAnnotations(clazz.getName());
            throw new InternalServerErrorException(msg);
         }
         registry = new LocatorRegistry(clazz, providerFactory);
         cachedSubresources.putIfAbsent(clazz, registry);
      }
      ResourceInvoker invoker = registry.getResourceInvoker(request);
      if (invoker instanceof ResourceLocatorInvoker)
      {
         ResourceLocatorInvoker locator = (ResourceLocatorInvoker) invoker;
         return locator.invoke(request, response, target);
      }
      else
      {
         ResourceMethodInvoker method = (ResourceMethodInvoker) invoker;
         return method.invoke(request, response, target);
      }
   }
}