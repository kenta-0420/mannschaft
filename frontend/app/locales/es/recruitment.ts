export default {
  "recruitment": {
    "category": {
      "futsal_open": "Fútbol sala individual",
      "soccer_open": "Fútbol individual",
      "basketball_open": "Baloncesto individual",
      "yoga_class": "Clase de yoga",
      "swimming_class": "Clase de natación",
      "dance_class": "Clase de baile",
      "fitness_class": "Fitness y entrenamiento",
      "match_opponent": "Buscar rival",
      "practice_match": "Buscar partido amistoso",
      "tournament": "Torneo",
      "referee": "Buscar árbitro",
      "staff": "Buscar personal/entrenador",
      "event_meeting": "Evento/encuentro",
      "workshop": "Taller/prueba",
      "other": "Otro"
    },
    "status": {
      "draft": "Borrador",
      "open": "Abierto",
      "full": "Completo",
      "closed": "Cerrado",
      "cancelled": "Cancelado"
    },
    "participantStatus": {
      "applied": "Solicitado",
      "confirmed": "Confirmado",
      "waitlisted": "Lista de espera",
      "cancelled": "Cancelado",
      "attended": "Asistido"
    },
    "participationType": {
      "individual": "Individual",
      "team": "Equipo"
    },
    "visibility": {
      "public": "Público",
      "scopeOnly": "Solo en el ámbito",
      "supportersOnly": "Solo seguidores"
    },
    "field": {
      "title": "Título",
      "description": "Descripción",
      "category": "Categoría",
      "subcategory": "Subcategoría",
      "startAt": "Inicio",
      "endAt": "Fin",
      "applicationDeadline": "Fecha límite de inscripción",
      "autoCancelAt": "Hora de cancelación automática",
      "capacity": "Capacidad",
      "minCapacity": "Capacidad mínima",
      "location": "Lugar",
      "price": "Precio",
      "paymentEnabled": "Pago habilitado",
      "cancellationPolicy": "Política de cancelación",
      "imageUrl": "URL de imagen",
      "note": "Nota",
      "participationType": "Tipo de participación",
      "visibility": "Visibilidad"
    },
    "action": {
      "create": "Crear",
      "publish": "Publicar",
      "edit": "Editar",
      "save": "Guardar",
      "cancel": "Cancelar",
      "archive": "Archivar",
      "apply": "Inscribirse",
      "cancelMyApplication": "Cancelar mi inscripción",
      "joinWaitlist": "Unirse a la lista de espera",
      "viewDetails": "Ver detalles",
      "createPolicy": "Crear política"
    },
    "confirmModal": {
      "cancellationFee": {
        "title": "Confirmación de tarifa de cancelación",
        "message": "Esta cancelación tendrá una tarifa. ¿Está seguro?",
        "feeAmountLabel": "Tarifa de cancelación",
        "freeMessage": "Puede cancelar gratis.",
        "agreeButton": "Acepto y cancelo",
        "disagreeButton": "Volver"
      },
      "publish": {
        "title": "Publicar convocatoria",
        "message": "¿Publicar esta convocatoria? Se notificará al ámbito de distribución."
      },
      "cancel": {
        "title": "Cancelar convocatoria",
        "message": "¿Cancelar esta convocatoria? Se notificará a todos los participantes."
      }
    },
    "policy": {
      "freeUntilHoursBefore": "Gratis hasta N horas antes del inicio",
      "freeUntilHoursBeforeHelp": "Ejemplo: 168 = gratis hasta 7 días antes",
      "addTier": "Añadir nivel",
      "removeTier": "Eliminar nivel",
      "tierOrder": "Nivel",
      "noPolicyMessage": "Sin política configurada"
    },
    "tier": {
      "percentage": "Porcentaje (%)",
      "fixed": "Cantidad fija (yenes)",
      "appliesAtOrBeforeHours": "Se aplica si quedan N horas o menos",
      "feeValue": "Valor de la tarifa",
      "feeType": "Tipo de tarifa"
    },
    "nav": {
      "recruitmentListings": "Convocatorias",
      "myRecruitmentListings": "Mis inscripciones",
      "createListing": "Crear convocatoria",
      "cancellationPolicies": "Políticas de cancelación"
    },
    "page": {
      "myRecruitmentListings": "Mis próximas participaciones",
      "teamRecruitmentListings": "Gestión de convocatorias",
      "newListing": "Crear convocatoria",
      "editListing": "Editar convocatoria",
      "listingDetail": "Detalle de la convocatoria",
      "cancellationPolicies": "Gestión de políticas de cancelación"
    },
    "label": {
      "participants": "Participantes",
      "remainingCapacity": "Plazas restantes",
      "waitlistCount": "Lista de espera",
      "noListings": "No hay convocatorias",
      "noParticipants": "No hay participantes",
      "myApplication": "Tu inscripción",
      "freeOfCharge": "Gratis"
    },
    "search": {
      "pageTitle": "Buscar Convocatorias",
      "keyword": "Palabras clave",
      "keywordPlaceholder": "Buscar por título o descripción",
      "location": "Ubicación",
      "locationPlaceholder": "Filtrar por ubicación",
      "participationType": "Tipo de participación",
      "allTypes": "Todos los tipos",
      "startFrom": "Fecha de inicio (Desde)",
      "startTo": "Fecha de inicio (Hasta)",
      "searchButton": "Buscar",
      "resetButton": "Restablecer",
      "allCategories": "Todas las categorías",
      "noResults": "No se encontraron convocatorias con esos criterios",
      "resultsCount": "{count} convocatorias",
      "capacity": "Capacidad",
      "remaining": "Quedan {count} plazas",
      "free": "Gratis",
      "priceLabel": "¥{price}",
      "applying": "Solicitando",
      "individual": "Individual",
      "team": "Equipo"
    },
    "error": {
      "RECRUITMENT_001": "Convocatoria no encontrada",
      "RECRUITMENT_002": "Sin permiso para crear convocatorias",
      "RECRUITMENT_003": "La visibilidad impide ver esta convocatoria",
      "RECRUITMENT_005": "Capacidad alcanzada",
      "RECRUITMENT_007": "Tipo de participación no coincide",
      "RECRUITMENT_008": "La capacidad mínima excede la capacidad",
      "RECRUITMENT_009": "El horario entra en conflicto con otra reserva o convocatoria",
      "RECRUITMENT_012": "Categoría no especificada",
      "RECRUITMENT_015": "El precio es obligatorio si el pago está habilitado",
      "RECRUITMENT_020": "Sin permiso para ver convocatorias en borrador",
      "RECRUITMENT_100": "Transición de estado inválida",
      "RECRUITMENT_101": "Fecha límite de inscripción superada",
      "RECRUITMENT_102": "Ya cancelado",
      "RECRUITMENT_103": "No se puede inscribir mientras esté en borrador",
      "RECRUITMENT_104": "Las convocatorias finalizadas no se pueden editar",
      "RECRUITMENT_105": "Ya inscrito",
      "RECRUITMENT_106": "Límite de lista de espera alcanzado",
      "RECRUITMENT_204": "No se puede publicar con cero objetivos de distribución",
      "RECRUITMENT_205": "URL de imagen no autorizada",
      "RECRUITMENT_206": "No se puede reducir la capacidad por debajo de los confirmados",
      "RECRUITMENT_207": "Visibilidad y objetivos de distribución incoherentes",
      "RECRUITMENT_301": "Pago de la tarifa de cancelación fallido",
      "RECRUITMENT_302": "Política de cancelación inválida",
      "RECRUITMENT_303": "La política de cancelación tiene más de 4 niveles",
      "RECRUITMENT_304": "Es necesario confirmar la tarifa de cancelación",
      "RECRUITMENT_307": "Los rangos de tiempo de los niveles se solapan",
      "RECRUITMENT_308": "La tarifa mostrada difiere de la real. Vuelva a calcular",
      "RECRUITMENT_305": "El plazo para disputar ha expirado",
      "RECRUITMENT_309": "Registro NO_SHOW no encontrado",
      "RECRUITMENT_310": "Penalización no encontrada",
      "RECRUITMENT_311": "Disputa ya presentada",
      "RECRUITMENT_312": "Configuración de penalización no encontrada"
    },
    "noShow": {
      "pageTitle": "Historial NO_SHOW",
      "adminPageTitle": "Gestión NO_SHOW",
      "status": {
        "pending": "Pendiente",
        "confirmed": "Confirmado",
        "disputed": "En disputa",
        "revoked": "Revocado",
        "upheld": "Mantenido"
      },
      "reason": {
        "adminMarked": "Marcado por admin",
        "autoDetected": "Detectado automáticamente"
      },
      "disputeButton": "Disputar",
      "disputeDialog": {
        "title": "Presentar disputa",
        "reasonLabel": "Razón",
        "reasonPlaceholder": "Ingrese razón específica",
        "submit": "Enviar"
      },
      "resolveDialog": {
        "title": "Resolver disputa",
        "revoke": "Revocar (REVOKED)",
        "uphold": "Mantener (UPHELD)",
        "adminNote": "Nota del admin",
        "submit": "Resolver"
      }
    },
    "penalty": {
      "pageTitle": "Configuración de penalización",
      "penaltiesPageTitle": "Lista de penalizaciones",
      "enabled": "Habilitado",
      "disabled": "Deshabilitado",
      "thresholdCount": "Umbral de incidencias",
      "thresholdPeriodDays": "Período (días)",
      "penaltyDurationDays": "Duración (días)",
      "autoDetection": "Detección automática",
      "disputeAllowedDays": "Plazo de disputa (días)",
      "liftButton": "Levantar penalización",
      "saveButton": "Guardar configuración",
      "liftDialog": {
        "title": "Levantar penalización",
        "adminManual": "Levantamiento manual por admin"
      },
      "status": {
        "active": "Activo",
        "expired": "Expirado",
        "lifted": "Levantado"
      }
    }
  }
}
