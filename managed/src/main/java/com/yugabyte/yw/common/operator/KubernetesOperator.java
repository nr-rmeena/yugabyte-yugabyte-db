// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common.operator;

import com.google.inject.Inject;
import com.yugabyte.yw.controllers.handlers.CloudProviderHandler;
import com.yugabyte.yw.controllers.handlers.UniverseCRUDHandler;
import com.yugabyte.yw.controllers.handlers.UpgradeUniverseHandler;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Class for Operator, you can run this sample using this command:
 *
 * <p>mvn exec:java -Dexec.mainClass= io.fabric8.ybUniverse.operator.YBUniverseOperatorMain
 */
public class KubernetesOperator {
  @Inject private UniverseCRUDHandler universeCRUDHandler;

  @Inject private UpgradeUniverseHandler upgradeUniverseHandler;

  @Inject private CloudProviderHandler cloudProviderHandler;

  public MixedOperation<YBUniverse, KubernetesResourceList<YBUniverse>, Resource<YBUniverse>>
      ybUniverseClient;

  public static final Logger LOG = LoggerFactory.getLogger(KubernetesOperator.class);

  public void init(String namespace) {
    LOG.info("Creating KubernetesOperator thread");
    Thread kubernetesOperatorThread =
        new Thread(
            () -> {
              try {
                long startTime = System.currentTimeMillis();
                LOG.info("Creating KubernetesOperator");
                try (KubernetesClient client = new KubernetesClientBuilder().build()) {
                  // maybe use: try (KubernetesClient client = getClient(config))
                  LOG.info("Using namespace : {}", namespace);

                  this.ybUniverseClient = client.resources(YBUniverse.class);
                  SharedIndexInformer<YBUniverse> ybUniverseSharedIndexInformer;
                  long resyncPeriodInMillis = 10 * 60 * 1000L;
                  SharedInformerFactory informerFactory = client.informers();

                  if (!namespace.trim().isEmpty()) {
                    // Listen to only one namespace.
                    ybUniverseSharedIndexInformer =
                        client
                            .resources(YBUniverse.class)
                            .inNamespace(namespace)
                            .inform(
                                new ResourceEventHandler<>() {
                                  @Override
                                  public void onAdd(YBUniverse Ybu) {}

                                  @Override
                                  public void onUpdate(YBUniverse Ybu, YBUniverse newYbu) {}

                                  @Override
                                  public void onDelete(
                                      YBUniverse Ybu, boolean deletedFinalUnknown) {}
                                },
                                resyncPeriodInMillis);
                  } else {
                    // Listen to all namespaces, use the factory to build informer.
                    ybUniverseSharedIndexInformer =
                        informerFactory.sharedIndexInformerFor(
                            YBUniverse.class, resyncPeriodInMillis);
                  }
                  LOG.info("Finished setting up SharedIndexInformers");

                  // TODO: Instantiate this - inject this using Module.java
                  KubernetesOperatorController ybUniverseController =
                      new KubernetesOperatorController(
                          client,
                          ybUniverseClient,
                          ybUniverseSharedIndexInformer,
                          universeCRUDHandler,
                          upgradeUniverseHandler,
                          cloudProviderHandler);

                  Future<Void> startedInformersFuture =
                      informerFactory.startAllRegisteredInformers();

                  startedInformersFuture.get();
                  ybUniverseController.run();

                  LOG.info("Finished running ybUniverseController");
                } catch (KubernetesClientException | ExecutionException exception) {
                  LOG.error("Kubernetes Client Exception : ", exception);
                } catch (InterruptedException interruptedException) {
                  LOG.error("Interrupted: ", interruptedException);
                  Thread.currentThread().interrupt();
                }
              } catch (Exception e) {
                LOG.error("Error", e);
              }
            });
    kubernetesOperatorThread.start();
  }
}
