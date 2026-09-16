# User-study runtime

## 1. Conditions

### History-aware

```text
current image
candidate handles
causal synthetic/live observations
committed receipts
```

### Scene-only

```text
same current image
same candidate handles
no long-history observations or receipts
```

Participant-visible messages, task instructions and props remain identical.

## 2. Scheduler control

The same frozen scheduler is used in both conditions. It cannot read the true
condition name; the operator sees only condition codes such as A/B.

## 3. Shadow inference

An optional unassigned-condition shadow call may run after the participant-visible
path without blocking it. Shadow output is never displayed and is clearly marked
counterfactual. Disable it if it affects latency or network load.

## 4. Silence measurement

After selected silent checkpoints, ask:

```text
Was it appropriate for the assistant to stay silent just now?
Would you have wanted unsolicited help at that moment?
```

This is necessary because NO_SERVICE is an active system behavior.

## 5. System metrics

```text
capture request to image receipt
image receipt to anchor emission
anchor to model response
response to visible display
technical failure rate
service-card display success
receipt suppression success
material-change reactivation success
anchor rate per hour
```

## 6. Study claims

The study validates interaction feasibility and perceived timing. The benchmark
continues to carry the main systematic model evaluation.
