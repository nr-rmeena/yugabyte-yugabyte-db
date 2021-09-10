---
title: Manage your billing profile and payment method
linkTitle: Billing
description: Manage your Yugabyte Cloud billing profile and payment methods and view invoices.
headcontent:
image: /images/section_icons/deploy/enterprise.png
menu:
  latest:
    identifier: cloud-billing-profile
    parent: cloud-admin
    weight: 200
isTocNested: true
showAsideToc: true
---

Review your cloud charges and manage your billing profile and payment methods using the **Billing** tab.

Only the cloud account Admin user (the user who created the Yugabyte Cloud account) can access the billing and payment details.

Yugabyte bills for its services as follows:

- Charges by the minute for your Yugabyte Cloud clusters.
- Tabulates costs daily.
- Displays your current monthly costs under **Invoices** on the **Billing** tab.

Yugabyte Cloud Billing is based on your actual usage across the following dimensions:

- Instance Capacity
- Disk Storage
- Backup Storage
- Data Transfer
 
**Invoices are sent to the email provided in the billing profile**. Refer to [Cluster costs](../cloud-billing-costs) for a summary of how cluster configurations are costed.

![Admin Billing tab](/images/yb-cloud/cloud-admin-billing.png)

The **Billing** tab has the following sections: **Profile and Payment Methods**, and **Invoices**.

## Profile and Payment Methods

Use this section to manage your contact information and payment method. You can pay using a credit card, or other payment methods such as debit, ACH, and invoicing.

Credit cards are self service. For other payment methods, create your billing profile and set the **Billing Options** to **Other**; once you have created your profile, contact [Yugabyte Support](https://support.yugabyte.com/hc/en-us/requests/new?ticket_form_id=360003113431) to set up payment.

If you want to switch from paying by credit card to another method, contact [Yugabyte Support](https://support.yugabyte.com/hc/en-us/requests/new?ticket_form_id=360003113431).

### Add or edit your Billing Profile

To add or change your billing profile:

1. On the **Billing** tab, select **Profile and Payment Methods** and click **Edit Billing Profile** to display the **Edit Billing Profile** sheet. If you haven't yet created a billing profile, click **Create Billing Profile**. 
1. Edit your contact information.
1. If you are creating your billing profile, enter your credit card details. For other payment methods, set the **Billing Options** to **Other**. You can only set the **Billing Options** if you are creating your profile.
1. Click **Save**.

### Manage credit cards

To add a credit card:

1. On the **Billing** tab, select **Profile and Payment Methods** and click **Add Card** to display the **Add Credit Card** dialog. 
1. Enter your credit card details.
1. To use the card as the default for payment, choose **Set as default credit card**.
1. Click **Save**.

To delete a card, click the **Delete** icon, then click **Confirm**. You cannot delete the default card.

### View credits

The **Credits** section displays any credits applied to your account, including the expiration date, name, amount used, and amount remaining. Credits are automatically applied to the next invoice.

## Invoices

This section lists your current and past invoices, along with a summary of cloud usage.

Select an invoice in the list to view a detailed breakdown of costs and usage details.

<!--
## Cost Estimator

Use the cost estimator to see estimated monthly and yearly pricing for for your cloud, based on configuration, backup, and data transfer settings.
-->
