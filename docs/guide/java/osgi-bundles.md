---
layout: website-normal
title: Creating an OSGi bundle
---

## OSGi bundles for custom java entities

When adding items to Brooklyn's [Catalog]({{site.path.guide}}/ops/catalog/index.html), Brooklyn
supports the inclusion of libraries which have been compiled as OSGi bundles.

In order to build custom entities as OSGi bundles, the pom.xml for your entity must include
the `maven-bundle-plugin` plugin in the `plugins` section.

For entities created with more recent versions of the Brooklyn maven archetype, the plugin
is included automatically, otherwise, you will need to add it to the `plugins` and
`pluginManagement` sections as follows:

{% highlight xml %}

<pluginManagement>

  ...

  <plugin>
    <groupId>org.apache.felix</groupId>
    <artifactId>maven-bundle-plugin</artifactId>
    <version>2.3.4</version>
  </plugin>
</pluginManagement>

<plugins>

  ...

  <plugin>
    <groupId>org.apache.felix</groupId>
    <artifactId>maven-bundle-plugin</artifactId>
    <extensions>true</extensions>
    <executions>
      <execution>
        <id>bundle-manifest</id>
        <phase>process-classes</phase>
        <goals>
          <goal>manifest</goal>
        </goals>
      </execution>
    </executions>
    <configuration>
      <supportedProjectTypes>
      <supportedProjectType>jar</supportedProjectType>
    </supportedProjectTypes>
    </configuration>
  </plugin>

</plugins>
{% endhighlight %}

After rebuilding your entity, a new folder will be created at `target/classes/META_INF`. This
will indicate that the plugin has been executed and the jar file in `target/` can be used
in a catalog item.
