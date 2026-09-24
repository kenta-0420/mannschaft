export default {
  "recruitment": {
    "category": {
      "futsal_open": "Futsal Open",
      "soccer_open": "Soccer Open",
      "basketball_open": "Basketball Open",
      "yoga_class": "Yoga Class",
      "swimming_class": "Swimming Class",
      "dance_class": "Dance Class",
      "fitness_class": "Fitness Training",
      "match_opponent": "Match Opponent Wanted",
      "practice_match": "Practice Match Wanted",
      "tournament": "Tournament",
      "referee": "Referee Wanted",
      "staff": "Staff/Coach Wanted",
      "event_meeting": "Event/Meetup",
      "workshop": "Workshop/Trial",
      "other": "Other"
    },
    "status": {
      "draft": "Draft",
      "open": "Open",
      "full": "Full",
      "closed": "Closed",
      "cancelled": "Cancelled",
      "auto_cancelled": "Automatically cancelled",
      "completed": "Completed"
    },
    "participantStatus": {
      "applied": "Applied",
      "confirmed": "Confirmed",
      "waitlisted": "Waitlisted",
      "cancelled": "Cancelled",
      "attended": "Attended"
    },
    "participationType": {
      "individual": "Individual",
      "team": "Team"
    },
    "visibility": {
      "public": "Public",
      "scopeOnly": "Scope only",
      "supportersOnly": "Supporters only"
    },
    "field": {
      "title": "Title",
      "description": "Description",
      "category": "Category",
      "subcategory": "Subcategory",
      "startAt": "Start time",
      "endAt": "End time",
      "applicationDeadline": "Application deadline",
      "autoCancelAt": "Auto-cancel time",
      "capacity": "Capacity",
      "minCapacity": "Minimum capacity",
      "location": "Location",
      "price": "Price",
      "paymentEnabled": "Payment enabled",
      "payeeKind": "Payee type",
      "payeeKindUser": "Individual (user)",
      "payeeKindTeam": "Team",
      "payeeKindOrg": "Organization",
      "payeeUser": "Payee",
      "payeeUserPlaceholder": "Select payee",
      "cancellationPolicy": "Cancellation policy",
      "imageUrl": "Image URL",
      "note": "Note",
      "participationType": "Participation type",
      "visibility": "Visibility"
    },
    "action": {
      "create": "Create",
      "publish": "Publish",
      "edit": "Edit",
      "save": "Save",
      "cancel": "Cancel",
      "archive": "Archive",
      "apply": "Apply",
      "cancelMyApplication": "Cancel my application",
      "joinWaitlist": "Join waitlist",
      "viewDetails": "View details",
      "confirmApplication": "Confirm",
      "cancelListing": "Withdraw recruitment",
      "cancelledListing": "Recruitment withdrawn",
      "createPolicy": "Create policy"
    },
    "confirmModal": {
      "cancellationFee": {
        "title": "Cancellation fee confirmation",
        "message": "A cancellation fee will apply. Are you sure?",
        "feeAmountLabel": "Cancellation fee",
        "freeMessage": "You can cancel for free.",
        "agreeButton": "Agree and cancel",
        "disagreeButton": "Back"
      },
      "publish": {
        "title": "Publish recruitment",
        "message": "Publish this recruitment? Subscribers will be notified."
      },
      "cancel": {
        "title": "Cancel recruitment",
        "message": "Cancel this recruitment? All participants will be notified."
      }
    },
    "policy": {
      "freeUntilHoursBefore": "Free until N hours before start",
      "freeUntilHoursBeforeHelp": "Example: 168 = free up to 7 days before",
      "addTier": "Add tier",
      "removeTier": "Remove tier",
      "tierOrder": "Tier",
      "noPolicyMessage": "No policy configured"
    },
    "tier": {
      "percentage": "Percentage (%)",
      "fixed": "Fixed amount (yen)",
      "appliesAtOrBeforeHours": "Applies at or before (N hours before)",
      "feeValue": "Fee value",
      "feeType": "Fee type"
    },
    "nav": {
      "recruitmentListings": "Recruitment listings",
      "myRecruitmentListings": "My applications",
      "createListing": "Create listing",
      "cancellationPolicies": "Cancellation policies"
    },
    "page": {
      "myRecruitmentListings": "My upcoming participations",
      "teamRecruitmentListings": "Recruitment management",
      "newListing": "Create recruitment",
      "editListing": "Edit recruitment",
      "listingDetail": "Recruitment detail",
      "cancellationPolicies": "Cancellation policy management",
      "myFeed": "Recruitment Feed"
    },
    "label": {
      "participants": "Participants",
      "remainingCapacity": "Remaining",
      "waitlistCount": "Waitlist",
      "noListings": "No recruitment listings",
      "noParticipants": "No participants",
      "myApplication": "Your application",
      "freeOfCharge": "Free",
      "feedDescription": "New recruitments from followed/supported teams",
      "noFeedItems": "No new recruitments",
      "listing": "Listing",
      "waitlistPosition": "Waitlist #{n}",
      "postedAt": "Posted",
      "loadError": "Failed to load data",
      "listingLabel": "Listing #{id}"
    },
    "guide": {
      "feed": {
        "title": "How to use New Recruitments",
        "what": {
          "title": "What is the recruitment feed?",
          "body": "A read-only feed showing new recruitments from teams you follow or support, sorted newest first. Use it to discover recruitments that interest you."
        },
        "apply": {
          "title": "View and apply",
          "body": "Tap a card to open the recruitment's detail page. You apply or join the waitlist from that detail page."
        },
        "read": {
          "title": "Reading the cards",
          "body": "Tags such as \"Open\" or \"Full\" show the status. Numbers are shown as \"confirmed / capacity\", and prices are shown with \"¥\"."
        }
      },
      "listings": {
        "title": "How to use My Participations",
        "what": {
          "title": "What are my participations?",
          "body": "Recruitments you have applied for or been confirmed on appear here. Only active participations are shown; cancelled or finished ones are excluded."
        },
        "status": {
          "title": "Reading the status",
          "body": "Tags such as \"Confirmed\", \"Applied\" or \"Waitlisted\" show your current state. When waitlisted, \"#rank\" shows your position in line."
        },
        "detail": {
          "title": "Details and cancellation",
          "body": "Open the recruitment page via \"View details\". Actions such as cancelling your application are done from that detail page."
        }
      }
    },
    "distribution": {
      "title": "Distribution targets",
      "members": "Members",
      "membersDesc": "Notify all members in scope",
      "supporters": "Supporters",
      "supportersDesc": "Notify approved supporters",
      "followers": "Followers",
      "followersDesc": "Notify followers",
      "publicFeed": "Public feed",
      "publicFeedDesc": "Post to public feed",
      "warnPublicNeedsFeed": "Public visibility requires PUBLIC_FEED target",
      "warnSupportersOnlyNeedsSupporter": "Supporters-only visibility requires SUPPORTERS target"
    },
    "widget": {
      "feedTitle": "New Recruitments",
      "feedEmpty": "No new recruitments",
      "myRecruitmentsTitle": "My Participations",
      "myRecruitmentsEmpty": "No upcoming participations",
      "viewAll": "View all"
    },
    "search": {
      "pageTitle": "Find Recruitments",
      "keyword": "Keyword",
      "keywordPlaceholder": "Search by title or description",
      "location": "Location",
      "locationPlaceholder": "Filter by location",
      "participationType": "Participation Type",
      "allTypes": "All Types",
      "startFrom": "Start Date (From)",
      "startTo": "Start Date (To)",
      "searchButton": "Search",
      "resetButton": "Reset",
      "allCategories": "All Categories",
      "noResults": "No recruitments found matching your criteria",
      "resultsCount": "{count} recruitments",
      "capacity": "Capacity",
      "remaining": "{count} spots left",
      "free": "Free",
      "priceLabel": "¥{price}",
      "applying": "Applying",
      "individual": "Individual",
      "team": "Team"
    },
    "validation": {
      "eventTimeRange": "The end time must be after the start time",
      "applicationDeadline": "The application deadline must be before the start time",
      "autoCancelAt": "The auto-cancel time must be on or before the application deadline",
      "capacity": "Minimum capacity must not exceed capacity"
    },
    "payee": {
      "required": "Please select a payee type",
      "userRequired": "Please select a payee user",
      "PAYMENT_C011": "Payee type (payeeKind) is not specified",
      "PAYMENT_C012": "Payee user is not specified",
      "PAYMENT_C013": "Specified payee is not a member of this scope"
    },
    "error": {
      "RECRUITMENT_001": "Recruitment not found",
      "RECRUITMENT_002": "No permission to create recruitment",
      "RECRUITMENT_003": "Visibility prevents you from viewing this recruitment",
      "RECRUITMENT_005": "Capacity has been reached",
      "RECRUITMENT_007": "Participation type mismatch",
      "RECRUITMENT_008": "Minimum capacity exceeds capacity",
      "RECRUITMENT_009": "Time conflicts with reservation line or another recruitment",
      "RECRUITMENT_012": "Category not specified",
      "RECRUITMENT_015": "Price is required when payment is enabled",
      "RECRUITMENT_020": "No permission to view draft recruitment",
      "RECRUITMENT_100": "Invalid state transition",
      "RECRUITMENT_101": "Application deadline has passed",
      "RECRUITMENT_102": "Already cancelled",
      "RECRUITMENT_103": "Cannot apply while in draft state",
      "RECRUITMENT_104": "Completed recruitment cannot be edited",
      "RECRUITMENT_105": "Already applied",
      "RECRUITMENT_106": "Waitlist limit exceeded",
      "RECRUITMENT_204": "Cannot publish with zero distribution targets",
      "RECRUITMENT_205": "Image URL is not whitelisted",
      "RECRUITMENT_206": "Cannot reduce capacity below confirmed count",
      "RECRUITMENT_207": "Visibility and distribution targets are inconsistent",
      "RECRUITMENT_216": "The event end time must be after the start time",
      "RECRUITMENT_217": "The application deadline must be before the event starts",
      "RECRUITMENT_218": "The auto-cancel time must be no later than the application deadline",
      "RECRUITMENT_301": "Cancellation fee payment failed",
      "RECRUITMENT_302": "Invalid cancellation policy",
      "RECRUITMENT_303": "Cancellation policy has more than 4 tiers",
      "RECRUITMENT_304": "Cancellation fee acknowledgement required",
      "RECRUITMENT_307": "Cancellation policy tier ranges overlap",
      "RECRUITMENT_308": "Displayed cancellation fee differs from actual. Please re-estimate",
      "RECRUITMENT_305": "Dispute deadline has passed",
      "RECRUITMENT_309": "NO_SHOW record not found",
      "RECRUITMENT_310": "Penalty not found",
      "RECRUITMENT_311": "Dispute already filed",
      "RECRUITMENT_312": "Penalty settings not found"
    },
    "noShow": {
      "pageTitle": "NO_SHOW History",
      "adminPageTitle": "NO_SHOW Management",
      "status": {
        "pending": "Pending",
        "confirmed": "Confirmed",
        "disputed": "Disputed",
        "revoked": "Revoked",
        "upheld": "Upheld"
      },
      "reason": {
        "adminMarked": "Admin Marked",
        "autoDetected": "Auto Detected"
      },
      "disputeButton": "File Dispute",
      "disputeDialog": {
        "title": "File Dispute",
        "reasonLabel": "Reason",
        "reasonPlaceholder": "Enter specific reason",
        "submit": "Submit"
      },
      "resolveDialog": {
        "title": "Resolve Dispute",
        "revoke": "Revoke (REVOKED)",
        "uphold": "Uphold (UPHELD)",
        "adminNote": "Admin Note",
        "submit": "Resolve"
      }
    },
    "penalty": {
      "pageTitle": "Penalty Settings",
      "penaltiesPageTitle": "Penalty List",
      "enabled": "Enabled",
      "disabled": "Disabled",
      "thresholdCount": "Threshold Count",
      "thresholdPeriodDays": "Period (days)",
      "penaltyDurationDays": "Penalty Duration (days)",
      "autoDetection": "Auto Detection",
      "disputeAllowedDays": "Dispute Allowed (days)",
      "liftButton": "Lift Penalty",
      "saveButton": "Save Settings",
      "liftDialog": {
        "title": "Lift Penalty",
        "adminManual": "Admin Manual Lift"
      },
      "status": {
        "active": "Active",
        "expired": "Expired",
        "lifted": "Lifted"
      }
    },
    "cancellationFeeWaive": {
      "pageTitle": "Waive Cancellation Fee",
      "pageDescription": "Waive cancellation fees you are due to receive",
      "unknownUser": "Unknown user",
      "loadMore": "Load more",
      "reasonTooLong": "The reason must be {max} characters or fewer",
      "columns": {
        "listing": "Listing",
        "user": "Target User",
        "feeAmount": "Cancellation Fee",
        "status": "Status",
        "cancelledAt": "Cancelled At"
      },
      "status": {
        "pending": "Unpaid",
        "failed": "Payment Failed",
        "uncollectible": "Uncollectible",
        "paid": "Paid",
        "waived": "Waived",
        "notRequired": "Not Applicable"
      },
      "waiveButton": "Waive",
      "reasonLabel": "Reason for Waiver",
      "reasonPlaceholder": "Enter the reason for waiving (required)",
      "reasonRequired": "A reason is required",
      "confirmDialog": {
        "title": "Waive Cancellation Fee",
        "message": "This will cancel the {amount} yen cancellation fee charge. This action cannot be undone.\nIf this user has other unpaid cancellation fees, the application restriction will not be lifted.",
        "confirmButton": "Waive",
        "cancelButton": "Back"
      },
      "emptyMessage": "No waivable cancellation fee records",
      "loadError": "Failed to load the list",
      "waiveSuccess": "Cancellation fee waived",
      "waiveError": "Failed to waive the fee"
    }
  }
}
