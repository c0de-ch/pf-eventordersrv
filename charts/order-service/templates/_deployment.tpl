{{/*
One Deployment definition used for the combined deployment and for the api/worker split.
Parameters: root (chart context), name, component (api|worker), replicas, workerEnabled, relayEnabled
*/}}
{{- define "order-service.deployment" -}}
{{- $root := .root -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .name }}
  labels:
    {{- include "order-service.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
spec:
  {{- if not (and $root.Values.autoscaling.enabled (eq .component "api")) }}
  replicas: {{ .replicas }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "order-service.selectorLabels" $root | nindent 6 }}
      app.kubernetes.io/component: {{ .component }}
  template:
    metadata:
      labels:
        {{- include "order-service.selectorLabels" $root | nindent 8 }}
        app.kubernetes.io/component: {{ .component }}
        {{- with $root.Values.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      annotations:
        checksum/config: {{ include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum }}
        {{- with $root.Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      {{- with $root.Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      serviceAccountName: {{ include "order-service.serviceAccountName" $root }}
      securityContext:
        {{- toYaml $root.Values.podSecurityContext | nindent 8 }}
      terminationGracePeriodSeconds: {{ $root.Values.terminationGracePeriodSeconds }}
      containers:
        - name: order-service
          image: {{ include "order-service.image" $root }}
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          securityContext:
            {{- toYaml $root.Values.securityContext | nindent 12 }}
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "order-service.fullname" $root }}
          env:
            - name: ORDERS_WORKER_ENABLED
              value: {{ .workerEnabled | quote }}
            - name: ORDERS_OUTBOX_RELAY_ENABLED
              value: {{ .relayEnabled | quote }}
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "order-service.secretName" $root }}
                  key: DB_PASSWORD
            - name: ARTEMIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "order-service.secretName" $root }}
                  key: ARTEMIS_PASSWORD
            {{- with $root.Values.extraEnv }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          startupProbe:
            httpGet:
              path: {{ $root.Values.probes.startup.path }}
              port: http
            periodSeconds: {{ $root.Values.probes.startup.periodSeconds }}
            failureThreshold: {{ $root.Values.probes.startup.failureThreshold }}
          livenessProbe:
            httpGet:
              path: {{ $root.Values.probes.liveness.path }}
              port: http
            periodSeconds: {{ $root.Values.probes.liveness.periodSeconds }}
          readinessProbe:
            httpGet:
              path: {{ $root.Values.probes.readiness.path }}
              port: http
            periodSeconds: {{ $root.Values.probes.readiness.periodSeconds }}
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep {{ $root.Values.preStopSleepSeconds }}"]
          resources:
            {{- toYaml $root.Values.resources | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
      {{- with $root.Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $root.Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $root.Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
