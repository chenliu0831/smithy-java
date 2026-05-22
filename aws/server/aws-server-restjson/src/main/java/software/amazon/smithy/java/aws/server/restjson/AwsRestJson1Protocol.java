/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.aws.server.restjson;

import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait;
import software.amazon.smithy.java.aws.server.restjson.router.UriMatcherMap;
import software.amazon.smithy.java.aws.server.restjson.router.UriMatcherMapBuilder;
import software.amazon.smithy.java.aws.server.restjson.router.UriPattern;
import software.amazon.smithy.java.aws.server.restjson.router.UriTreeMatcherMap;
import software.amazon.smithy.java.aws.server.restjson.router.ValuedMatch;
import software.amazon.smithy.java.context.Context;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.core.schema.TraitKey;
import software.amazon.smithy.java.core.serde.Codec;
import software.amazon.smithy.java.http.api.HttpHeaders;
import software.amazon.smithy.java.http.api.HttpRequest;
import software.amazon.smithy.java.http.api.HttpResponse;
import software.amazon.smithy.java.http.binding.HttpBinding;
import software.amazon.smithy.java.http.binding.ResponseSerializer;
import software.amazon.smithy.java.io.uri.URLEncoding;
import software.amazon.smithy.java.json.JsonCodec;
import software.amazon.smithy.java.server.Operation;
import software.amazon.smithy.java.server.Service;
import software.amazon.smithy.java.server.core.HttpJob;
import software.amazon.smithy.java.server.core.Job;
import software.amazon.smithy.java.server.core.RouteSpec;
import software.amazon.smithy.java.server.core.ServerProtocol;
import software.amazon.smithy.java.server.core.ServiceProtocolResolutionRequest;
import software.amazon.smithy.java.server.core.ServiceProtocolResolutionResult;
import software.amazon.smithy.model.pattern.SmithyPattern;
import software.amazon.smithy.model.shapes.ShapeId;

final class AwsRestJson1Protocol extends ServerProtocol {

    private static final Context.Key<
            ValuedMatch<Operation<? extends SerializableStruct, ? extends SerializableStruct>>> MATCH_KEY = Context
                    .key("Aws Rest Json1 Valued Match");

    private final Codec codec;
    private final Map<String, UriMatcherMap<Operation<?, ?>>> httpMethodToMatchersMap;
    private final HttpBinding httpBinding = new HttpBinding();
    private final List<RouteSpec> routes;

    AwsRestJson1Protocol(List<Service> services) {
        super(services);
        var routesList = new java.util.ArrayList<RouteSpec>();
        var httpMethodToMatchers = new HashMap<String, UriMatcherMapBuilder<Operation<?, ?>>>();
        for (Service service : services) {
            // restJson1 only owns operations with an @http trait — the
            // inner-loop httpTrait check below naturally excludes
            // operations that belong to other protocols. Filtering by
            // service-level protocol trait was tried but doesn't work:
            // codegen-generated services may carry no traits on the
            // service schema (the protocol shows up only on operations
            // and members).
            for (var operation : service.getAllOperations()) {
                // Only process operations with HTTP trait.
                var httpTrait = operation.getApiOperation()
                        .schema()
                        .getTrait(TraitKey.HTTP_TRAIT);
                if (httpTrait == null) {
                    continue;
                }
                String method = httpTrait.getMethod();
                String pattern = httpTrait
                        .getUri()
                        .toString();
                httpMethodToMatchers.computeIfAbsent(method, k -> UriTreeMatcherMap.builder())
                        .add(UriPattern.forSpecificityRouting(pattern), operation);
                routesList.add(new RouteSpec(method, smithyToVertxPath(pattern), service, operation));
            }
        }
        this.httpMethodToMatchersMap = httpMethodToMatchers.entrySet()
                .stream()
                .collect(toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().build()));
        this.codec = JsonCodec.builder()
                .useJsonName(true)
                .useTimestampFormat(true)
                .build();
        this.routes = List.copyOf(routesList);
    }

    @Override
    public List<RouteSpec> enumerateRoutes() {
        return routes;
    }

    /**
     * Translate a Smithy URI pattern to Vert.x route syntax. Smithy uses
     * {@code /order/{id}} (and {@code /order/{id+}} for greedy labels);
     * Vert.x uses {@code /order/:id} (and {@code /order/*} for greedy
     * tails).
     */
    private static String smithyToVertxPath(String smithyPattern) {
        // Strip query string portion if present; Vert.x routes match
        // path only, query handled by the underlying request.
        int qIdx = smithyPattern.indexOf('?');
        String path = qIdx >= 0 ? smithyPattern.substring(0, qIdx) : smithyPattern;
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '{') {
                int end = path.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated label in Smithy URI pattern: " + smithyPattern);
                }
                String label = path.substring(i + 1, end);
                if (label.endsWith("+")) {
                    // Greedy label: Vert.x has no per-segment greedy syntax,
                    // so use trailing wildcard. Any operation using a greedy
                    // label must be the last segment of the pattern.
                    out.append('*');
                } else {
                    out.append(':').append(label);
                }
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    @Override
    public ShapeId getProtocolId() {
        return RestJson1Trait.ID;
    }

    @Override
    public ServiceProtocolResolutionResult resolveOperation(
            ServiceProtocolResolutionRequest request,
            List<Service> candidates
    ) {
        var uri = request.uri().getPath();
        String rawQuery = request.uri().getRawQuery();
        if (rawQuery != null) {
            uri += "?" + rawQuery;
        }
        var matcher = httpMethodToMatchersMap.get(request.method());
        if (matcher == null) {
            return null;
        }
        ValuedMatch<Operation<? extends SerializableStruct, ? extends SerializableStruct>> selectedOperation = matcher
                .match(uri);
        if (selectedOperation == null) {
            return null;
        }
        request.requestContext().put(MATCH_KEY, selectedOperation);

        return new ServiceProtocolResolutionResult(
                selectedOperation.getValue().getOwningService(),
                selectedOperation.getValue(),
                this);

    }

    @Override
    public CompletableFuture<Void> deserializeInput(Job job) {
        if (!job.isHttpJob()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Unsupported Job type. Only HttpJob is supported"));
        }

        var httpJob = job.asHttpJob();
        var selectedOperation = job.request().context().get(MATCH_KEY);
        Map<String, String> labelValues = new HashMap<>();
        for (SmithyPattern.Segment label : job.operation()
                .getApiOperation()
                .schema()
                .expectTrait(TraitKey.HTTP_TRAIT)
                .getUri()
                .getLabels()) {
            var values = selectedOperation.getLabelValues(label.getContent());
            if (values != null) {
                labelValues.put(label.getContent(), URLEncoding.urlDecode(values.get(0)));
            }
        }
        HttpHeaders headers = httpJob.request().headers();
        var inputShapeBuilder = job.operation().getApiOperation().inputBuilder();
        var deser = httpBinding
                .requestDeserializer()
                .inputShapeBuilder(inputShapeBuilder)
                .pathLabelValues(labelValues)
                .request(
                        HttpRequest.create()
                                .setHeaders(headers)
                                .setUri(httpJob.request().uri())
                                .setMethod(httpJob.request().method())
                                .setBody(job.request().getDataStream()))
                .payloadCodec(codec)
                .payloadMediaType("application/json");

        try {
            deser.deserialize();
        } catch (Exception e) {
            //TODO do exception translation.
            return CompletableFuture.failedFuture(e);
        }

        job.request().setDeserializedValue(inputShapeBuilder.build());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> serializeOutput(Job job, SerializableStruct output, boolean isError) {
        HttpJob httpJob = (HttpJob) job; //We already check in the deserializeInput method.
        ResponseSerializer serializer = httpBinding.responseSerializer()
                .operation(job.operation().getApiOperation())
                .payloadCodec(codec)
                .payloadMediaType("application/json")
                .shapeValue(output);
        if (isError) {
            serializer.errorSchema(output.schema());
        }
        HttpResponse response = serializer.serializeResponse();
        httpJob.response().setSerializedValue(response.body());
        httpJob.response().setStatusCode(response.statusCode());
        httpJob.response().headers().addHeaders(response.headers());
        return CompletableFuture.completedFuture(null);
    }
}
