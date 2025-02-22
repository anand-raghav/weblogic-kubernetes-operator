---
title: "Handling security validations"
date: 2020-06-30T08:55:00-05:00
draft: false
weight: 14
description: "Why am I seeing these security warnings?"
---

> After applying the July2021 PSU, I'm now seeing security warnings, such as:
>
> Description: Production Mode is enabled but user lockout settings are not secure in realm: myrealm, i.e. LockoutThreshold should not be greater than 5, LockoutDuration should not be less than 30.
>
> SOLUTION: Update the user lockout settings (LockoutThreshold, LockoutDuration) to be secure.

WebLogic Server has a new, important feature to ensure and help you secure your WLS domains when running in production. With the July 2021 PSU applied, WebLogic Server regularly validates your domain configuration settings against a set of security configuration guidelines to determine whether the domain meets key security guidelines recommended by Oracle. For more information and additional details, see [MOS Doc 2788605.1](https://support.oracle.com/rs?type=doc&id=2788605.1) "WebLogic Server Security Warnings Displayed Through the Admin Console" and [Review Potential Security Issues](https://docs.oracle.com/en/middleware/fusion-middleware/weblogic-server/12.2.1.4/lockd/secure.html#GUID-4148D1BE-2D54-4DA5-8E94-A35D48DCEF1D) in _Securing a Production Environment for Oracle WebLogic Server_.

Warnings may be at the level of the JDK, or that SSL is not enabled. Some warnings may recommend updating your WebLogic configuration. You can make the recommended configuration changes using an approach that depends on your [domain home source type]({{< relref "/userguide/managing-domains/choosing-a-model/_index.md" >}}):

- For Domain in PV, use the WebLogic Scripting Tool (WLST), WebLogic Server Administration Console, WebLogic Deploy Tooling (WDT), or [configuration overrides]({{< relref "/userguide/managing-domains/configoverrides/_index.md" >}}).

- For Domain in Image, create a new image with the recommended changes or use [configuration overrides]({{< relref "/userguide/managing-domains/configoverrides/_index.md" >}}).

- For Model in Image, supply model files with the recommended changes in its image's `modelHome` directory or use [runtime updates]({{< relref "/userguide/managing-domains/model-in-image/runtime-updates.md" >}}).
