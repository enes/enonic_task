// Thin on purpose: pass the parameters to the Java bean and turn its answer into a response.
// The bean returns an explicit outcome; HTTP status codes belong to this controller.

function json(status, body) {
    return {
        status: status,
        contentType: 'application/json',
        body: body
    };
}

exports.get = function (req) {
    const params = req.params;

    const bean = __.newBean('com.enonic.assignment.inventory.InventoryBean');
    bean.setProject(__.nullOrValue(params.project));
    bean.setBranch(__.nullOrValue(params.branch));
    bean.setPath(__.nullOrValue(params.path));

    const result = __.toNativeObject(bean.execute());
    switch (result.status) {
        case 'SUCCESS':
            return json(200, result.inventory);
        case 'INVALID_INPUT':
            return json(400, {error: result.message});
        case 'NOT_FOUND':
            return json(404, {error: result.message});
        default:
            throw new Error('Unknown inventory result status: ' + result.status);
    }
};
